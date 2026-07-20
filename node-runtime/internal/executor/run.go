package executor

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"company.internal/loop-engine/node-runtime/internal/agent"
	"company.internal/loop-engine/node-runtime/internal/artifact"
	"company.internal/loop-engine/node-runtime/internal/process"
)

const (
	defaultMaxRepairRounds = 2
	phaseOneMaxRepairRounds = 2
	feedbackTailLines       = 200
)

// Validation is one validation command outcome.
type Validation struct {
	Program    string
	Args       []string
	ExitCode   int
	DurationMs int64
	TimedOut   bool
	StdoutPath string
	StderrPath string
}

// ValidationCommand is an argv-style validation step from the project profile.
type ValidationCommand struct {
	Program        string
	Args           []string
	TimeoutSeconds int
}

// Validator runs profile validation commands without a shell.
type Validator interface {
	Validate(ctx context.Context, workspace string, commands []ValidationCommand) ([]Validation, error)
}

// RunTask is one attempt execution request.
type RunTask struct {
	TaskID             string
	AttemptID          string
	NodeID             string
	BaseSHA            string
	Workspace          string
	OutDir             string
	Prompt             string
	Policy             agent.Policy
	MaxRepairRounds    int
	MaxChangedFiles    int
	MaxPatchBytes      int64
	ForbiddenPaths     []string
	ValidationCommands []ValidationCommand
	EventLog           artifact.EventLogRef
	TestReportPaths    []string
	Git                GitRunner
}

// RunResult is the terminal attempt outcome exposed to callers and tests.
type RunResult struct {
	Code     string
	Outcome  string
	Manifest artifact.Manifest
	Err      error
}

// GitRunner inspects the workspace after successful validation.
type GitRunner interface {
	StatusPorcelain(ctx context.Context, workspace string) ([]byte, error)
	DiffBinary(ctx context.Context, workspace string) ([]byte, error)
	DiffCheck(ctx context.Context, workspace string) error
}

// gitlinkInspector reports index paths recorded as gitlinks (mode 160000).
type gitlinkInspector interface {
	GitlinkPaths(ctx context.Context, workspace string) (map[string]struct{}, error)
}

// AttemptRun orchestrates agent sessions, validation feedback and Artifact emission.
type AttemptRun struct {
	agent     agent.Agent
	validator Validator
	builder   *artifact.Builder
}

// NewRun constructs an attempt orchestrator.
func NewRun(a agent.Agent, validator Validator, builder *artifact.Builder) *AttemptRun {
	if builder == nil {
		builder = artifact.NewBuilder()
	}
	return &AttemptRun{agent: a, validator: validator, builder: builder}
}

// Execute runs one initial agent session plus at most maxRepairRounds feedback sessions.
func (r *AttemptRun) Execute(ctx context.Context, task RunTask) RunResult {
	started := time.Now().UTC()
	rounds, err := normalizeRepairRounds(task.MaxRepairRounds)
	if err != nil {
		return failResult("INVALID_CONFIG", started, task, nil, err)
	}
	task.MaxRepairRounds = rounds
	if task.OutDir == "" {
		task.OutDir = filepath.Join(task.Workspace, "out")
	}
	if err := os.MkdirAll(task.OutDir, 0o755); err != nil {
		return failResult("IO_ERROR", started, task, nil, err)
	}
	if r.agent == nil || r.validator == nil {
		return failResult("INVALID_CONFIG", started, task, nil, fmt.Errorf("agent and validator required"))
	}

	prompt := task.Prompt
	var lastValidations []Validation
	agentStarts := 0
	maxStarts := 1 + task.MaxRepairRounds

	for agentStarts < maxStarts {
		if err := ctx.Err(); err != nil {
			return failResult("CANCELLED", started, task, lastValidations, err)
		}
		sess, err := r.agent.Start(ctx, agent.Task{Prompt: prompt}, task.Workspace, task.Policy)
		if err != nil {
			return failResult("AGENT_START_FAILED", started, task, lastValidations, err)
		}
		agentStarts++
		drainEvents(sess)
		agentResult := sess.Wait()
		if !agentResult.Success {
			code := agentResult.Code
			if code == "" {
				code = "AGENT_FAILED"
			}
			return failResult(code, started, task, lastValidations, agentResult.Err)
		}

		validations, err := r.validator.Validate(ctx, task.Workspace, task.ValidationCommands)
		if err != nil {
			return failResult("VALIDATION_ERROR", started, task, lastValidations, err)
		}
		lastValidations = validations
		if allValidationsPassed(validations) {
			return r.finishSuccess(ctx, task, started, validations)
		}

		if agentStarts > task.MaxRepairRounds {
			// agentStarts is 1-based; after initial + maxRepairRounds feedbacks we stop.
			break
		}
		if agentStarts >= maxStarts {
			break
		}
		prompt = buildFeedbackPrompt(validations)
	}

	finished := time.Now().UTC()
	manifest := artifact.Manifest{
		Protocol:          "v1",
		TaskID:            task.TaskID,
		AttemptID:         task.AttemptID,
		NodeID:            task.NodeID,
		BaseSHA:           task.BaseSHA,
		ValidationResults: toManifestValidations(lastValidations),
		EventLog:          task.EventLog,
		StartedAt:         started,
		FinishedAt:        finished,
		Outcome:           "FAILED",
		Code:              "VALIDATION_FAILED",
	}
	_ = r.builder.WriteFailedManifest(task.OutDir, manifest)
	return RunResult{Code: "VALIDATION_FAILED", Outcome: "FAILED", Manifest: manifest}
}

func (r *AttemptRun) finishSuccess(ctx context.Context, task RunTask, started time.Time, validations []Validation) RunResult {
	git := task.Git
	if git == nil {
		git = WorkspaceGit{}
	}
	status, err := git.StatusPorcelain(ctx, task.Workspace)
	if err != nil {
		return failResult("GIT_STATUS_FAILED", started, task, validations, err)
	}
	changed, err := parseStatusPorcelain(status)
	if err != nil {
		return failResult("GIT_STATUS_FAILED", started, task, validations, err)
	}
	if len(changed) == 0 {
		return failResult("NO_DIFF", started, task, validations, fmt.Errorf("no diff"))
	}
	if err := git.DiffCheck(ctx, task.Workspace); err != nil {
		return failResult("DIFF_CHECK_FAILED", started, task, validations, err)
	}
	patch, err := git.DiffBinary(ctx, task.Workspace)
	if err != nil {
		return failResult("GIT_DIFF_FAILED", started, task, validations, err)
	}
	if err := rejectSubmoduleChanges(ctx, git, task.Workspace, changed, patch); err != nil {
		return failResult("SUBMODULE_CHANGE", started, task, validations, err)
	}
	finished := time.Now().UTC()
	manifest, err := r.builder.BuildSuccess(artifact.BuildInput{
		Workspace:         task.Workspace,
		OutDir:            task.OutDir,
		TaskID:            task.TaskID,
		AttemptID:         task.AttemptID,
		NodeID:            task.NodeID,
		BaseSHA:           task.BaseSHA,
		Patch:             patch,
		ChangedFiles:      changed,
		ValidationResults: toManifestValidations(validations),
		EventLog:          task.EventLog,
		StartedAt:         started,
		FinishedAt:        finished,
		TestReportPaths:   task.TestReportPaths,
		MaxChangedFiles:   task.MaxChangedFiles,
		MaxPatchBytes:     task.MaxPatchBytes,
		ForbiddenPaths:    task.ForbiddenPaths,
	})
	if err != nil {
		return failResult("ARTIFACT_REJECTED", started, task, validations, err)
	}
	return RunResult{Code: "SUCCEEDED", Outcome: "SUCCEEDED", Manifest: manifest}
}

func failResult(code string, started time.Time, task RunTask, validations []Validation, err error) RunResult {
	finished := time.Now().UTC()
	manifest := artifact.Manifest{
		Protocol:          "v1",
		TaskID:            task.TaskID,
		AttemptID:         task.AttemptID,
		NodeID:            task.NodeID,
		BaseSHA:           task.BaseSHA,
		ValidationResults: toManifestValidations(validations),
		EventLog:          task.EventLog,
		StartedAt:         started,
		FinishedAt:        finished,
		Outcome:           "FAILED",
		Code:              code,
	}
	if task.OutDir != "" {
		_ = artifact.NewBuilder().WriteFailedManifest(task.OutDir, manifest)
	}
	return RunResult{Code: code, Outcome: "FAILED", Manifest: manifest, Err: err}
}

func normalizeRepairRounds(n int) (int, error) {
	if n == 0 {
		return defaultMaxRepairRounds, nil
	}
	if n < 0 {
		return 0, fmt.Errorf("maxRepairRounds must be >= 0")
	}
	if n > phaseOneMaxRepairRounds {
		return 0, fmt.Errorf("maxRepairRounds above %d rejected in phase one", phaseOneMaxRepairRounds)
	}
	return n, nil
}

func allValidationsPassed(results []Validation) bool {
	if len(results) == 0 {
		return true
	}
	for _, r := range results {
		if r.ExitCode != 0 || r.TimedOut {
			return false
		}
	}
	return true
}

func buildFeedbackPrompt(results []Validation) string {
	var b strings.Builder
	b.WriteString("Validation failed. Fix the failures below.\n")
	for _, r := range results {
		if r.ExitCode == 0 && !r.TimedOut {
			continue
		}
		fmt.Fprintf(&b, "command: %s", r.Program)
		for _, arg := range r.Args {
			fmt.Fprintf(&b, " %s", arg)
		}
		fmt.Fprintf(&b, "\nexitCode: %d\n", r.ExitCode)
		lines := collectTail(r)
		if len(lines) > 0 {
			b.WriteString("output:\n")
			for _, line := range lines {
				b.WriteString(redactSecrets(line))
				b.WriteByte('\n')
			}
		}
	}
	return b.String()
}

func collectTail(r Validation) []string {
	var lines []string
	for _, path := range []string{r.StdoutPath, r.StderrPath} {
		if path == "" {
			continue
		}
		part, err := process.ReadTailLines(path, feedbackTailLines)
		if err != nil {
			continue
		}
		lines = append(lines, part...)
	}
	if len(lines) > feedbackTailLines {
		lines = lines[len(lines)-feedbackTailLines:]
	}
	return lines
}

func redactSecrets(line string) string {
	redacted := line
	patterns := []string{
		"CI_JOB_TOKEN=", "GITLAB_TOKEN=", "PRIVATE-TOKEN:", "Bearer ",
		"ANTHROPIC_API_KEY=", "OPENAI_API_KEY=", "GOOGLE_GENERATIVE_AI_API_KEY=",
	}
	for _, p := range patterns {
		if i := strings.Index(redacted, p); i >= 0 {
			rest := redacted[i+len(p):]
			cut := len(rest)
			for j := 0; j < len(rest); j++ {
				if rest[j] == ' ' || rest[j] == '"' || rest[j] == '\'' {
					cut = j
					break
				}
			}
			redacted = redacted[:i+len(p)] + "***" + rest[cut:]
		}
	}
	return redacted
}

func toManifestValidations(in []Validation) []artifact.ValidationResult {
	out := make([]artifact.ValidationResult, 0, len(in))
	for _, v := range in {
		out = append(out, artifact.ValidationResult{
			Program:    v.Program,
			Args:       v.Args,
			ExitCode:   v.ExitCode,
			DurationMs: v.DurationMs,
		})
	}
	return out
}

func drainEvents(sess agent.Session) {
	if sess == nil {
		return
	}
	for range sess.Events() {
	}
}

// ProcessValidator executes validation commands via the process package.
type ProcessValidator struct{}

// Validate runs each command with timeout and capped stdout/stderr capture.
func (ProcessValidator) Validate(ctx context.Context, workspace string, commands []ValidationCommand) ([]Validation, error) {
	out := make([]Validation, 0, len(commands))
	logDir := filepath.Join(workspace, "out", "validation-logs")
	if err := os.MkdirAll(logDir, 0o755); err != nil {
		return nil, err
	}
	for i, cmd := range commands {
		stdoutPath := filepath.Join(logDir, fmt.Sprintf("%d.stdout.log", i))
		stderrPath := filepath.Join(logDir, fmt.Sprintf("%d.stderr.log", i))
		timeout := time.Duration(cmd.TimeoutSeconds) * time.Second
		if cmd.TimeoutSeconds <= 0 {
			timeout = 20 * time.Minute
		}
		res := process.Run(ctx, process.Spec{
			Program:    cmd.Program,
			Args:       cmd.Args,
			Dir:        workspace,
			Timeout:    timeout,
			StdoutPath: stdoutPath,
			StderrPath: stderrPath,
		})
		exit := res.ExitCode
		if res.TimedOut {
			exit = -1
		}
		out = append(out, Validation{
			Program:    cmd.Program,
			Args:       append([]string(nil), cmd.Args...),
			ExitCode:   exit,
			DurationMs: res.Duration.Milliseconds(),
			TimedOut:   res.TimedOut,
			StdoutPath: stdoutPath,
			StderrPath: stderrPath,
		})
	}
	return out, nil
}

// WorkspaceGit runs the exact Git argv sequence required after successful validation.
type WorkspaceGit struct{}

func (WorkspaceGit) StatusPorcelain(ctx context.Context, workspace string) ([]byte, error) {
	return runGitOutput(ctx, workspace, []string{"status", "--porcelain=v1", "-z"})
}

func (WorkspaceGit) DiffBinary(ctx context.Context, workspace string) ([]byte, error) {
	return runGitOutput(ctx, workspace, []string{"diff", "--binary", "--full-index", "--no-ext-diff", "HEAD", "--"})
}

func (WorkspaceGit) DiffCheck(ctx context.Context, workspace string) error {
	res := process.Run(ctx, process.Spec{
		Program: "git",
		Args:    []string{"diff", "--check"},
		Dir:     workspace,
		Timeout: 2 * time.Minute,
	})
	if res.Err != nil && res.ExitCode == 0 {
		return res.Err
	}
	if res.ExitCode != 0 {
		return fmt.Errorf("git diff --check exit %d", res.ExitCode)
	}
	return nil
}

// GitlinkPaths returns index paths with mode 160000 (git submodule / gitlink).
func (WorkspaceGit) GitlinkPaths(ctx context.Context, workspace string) (map[string]struct{}, error) {
	raw, err := runGitOutput(ctx, workspace, []string{"ls-files", "-s", "-z"})
	if err != nil {
		return nil, err
	}
	return parseGitlinkPaths(raw), nil
}

func parseGitlinkPaths(raw []byte) map[string]struct{} {
	out := make(map[string]struct{})
	if len(raw) == 0 {
		return out
	}
	for _, entry := range strings.Split(string(raw), "\x00") {
		if entry == "" {
			continue
		}
		// <mode> <object> <stage>\t<path>
		mode, path, ok := splitStageEntry(entry)
		if !ok {
			continue
		}
		if mode == "160000" {
			out[filepath.ToSlash(path)] = struct{}{}
		}
	}
	return out
}

func splitStageEntry(entry string) (mode, path string, ok bool) {
	parts := strings.SplitN(entry, "\t", 2)
	if len(parts) != 2 {
		return "", "", false
	}
	fields := strings.Fields(parts[0])
	if len(fields) < 1 {
		return "", "", false
	}
	return fields[0], parts[1], true
}

func rejectSubmoduleChanges(ctx context.Context, git GitRunner, workspace string, changed []artifact.ChangedFile, patch []byte) error {
	for _, cf := range changed {
		if cf.Status == "submodule" || isGitmodulesPath(cf.Path) {
			return fmt.Errorf("submodule changes forbidden: %s", cf.Path)
		}
	}
	if patchIndicatesGitlink(patch) {
		return fmt.Errorf("submodule changes forbidden: gitlink in patch")
	}
	if insp, ok := git.(gitlinkInspector); ok {
		links, err := insp.GitlinkPaths(ctx, workspace)
		if err != nil {
			return err
		}
		for _, cf := range changed {
			if _, hit := links[cf.Path]; hit {
				return fmt.Errorf("submodule changes forbidden: gitlink %s", cf.Path)
			}
		}
	}
	return nil
}

func isGitmodulesPath(path string) bool {
	return filepath.Base(path) == ".gitmodules"
}

func patchIndicatesGitlink(patch []byte) bool {
	if len(patch) == 0 {
		return false
	}
	s := string(patch)
	return strings.Contains(s, "mode 160000") || strings.Contains(s, "Subproject commit")
}

func runGitOutput(ctx context.Context, workspace string, args []string) ([]byte, error) {
	stdoutPath := filepath.Join(os.TempDir(), fmt.Sprintf("repair-git-stdout-%d", time.Now().UnixNano()))
	stderrPath := filepath.Join(os.TempDir(), fmt.Sprintf("repair-git-stderr-%d", time.Now().UnixNano()))
	defer os.Remove(stdoutPath)
	defer os.Remove(stderrPath)
	res := process.Run(ctx, process.Spec{
		Program:    "git",
		Args:       args,
		Dir:        workspace,
		Timeout:    5 * time.Minute,
		StdoutPath: stdoutPath,
		StderrPath: stderrPath,
	})
	data, readErr := os.ReadFile(stdoutPath)
	if res.Err != nil && res.ExitCode == -1 {
		return nil, res.Err
	}
	if res.ExitCode != 0 {
		errData, _ := os.ReadFile(stderrPath)
		return nil, fmt.Errorf("git %v exit %d: %s", args, res.ExitCode, string(errData))
	}
	if readErr != nil {
		return nil, readErr
	}
	return data, nil
}

func parseStatusPorcelain(raw []byte) ([]artifact.ChangedFile, error) {
	if len(raw) == 0 {
		return nil, nil
	}
	entries := strings.Split(string(raw), "\x00")
	out := make([]artifact.ChangedFile, 0, len(entries))
	for _, entry := range entries {
		if entry == "" {
			continue
		}
		if len(entry) < 4 {
			return nil, fmt.Errorf("invalid porcelain entry %q", entry)
		}
		xy := entry[:2]
		path := entry[3:]
		// Rename records use " -> " between old and new in non -z, but -z uses two NULs;
		// remaining path after first record component is the path we care about.
		if i := strings.IndexByte(path, 0); i >= 0 {
			path = path[i+1:]
		}
		status := "modified"
		switch {
		case xy[0] == '?' || xy[1] == '?':
			status = "added"
		case xy[0] == 'A' || xy[1] == 'A':
			status = "added"
		case xy[0] == 'D' || xy[1] == 'D':
			status = "deleted"
		case xy[0] == 'R' || xy[1] == 'R':
			status = "renamed"
		}
		if looksLikeSubmodule(path) {
			status = "submodule"
		}
		out = append(out, artifact.ChangedFile{Path: filepath.ToSlash(path), Status: status})
	}
	return out, nil
}

func looksLikeSubmodule(path string) bool {
	return isGitmodulesPath(path)
}
