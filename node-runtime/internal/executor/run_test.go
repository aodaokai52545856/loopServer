package executor

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	"company.internal/loop-engine/node-runtime/internal/agent"
	"company.internal/loop-engine/node-runtime/internal/artifact"
)

func TestFailedValidationIsFedBackAtMostTwice(t *testing.T) {
	workspace := t.TempDir()
	outDir := filepath.Join(workspace, "out")
	agent := &fakeAgent{results: []agent.Result{{Success: true}, {Success: true}, {Success: true}}}
	validator := &fakeValidator{results: []Validation{{ExitCode: 1}, {ExitCode: 1}, {ExitCode: 1}}}
	result := NewRun(agent, validator, artifact.NewBuilder()).Execute(context.Background(), taskWithMaxRounds(2, workspace, outDir))
	if result.Code != "VALIDATION_FAILED" {
		t.Fatalf("code = %s", result.Code)
	}
	if agent.Starts != 3 {
		t.Fatalf("initial plus repairs = %d", agent.Starts)
	}
	manifest, err := artifact.ReadManifest(outDir)
	if err != nil {
		t.Fatalf("failed manifest: %v", err)
	}
	if manifest.Outcome == "SUCCEEDED" {
		t.Fatalf("failed job must not claim success")
	}
	if _, err := os.Stat(artifact.PatchPath(outDir)); !os.IsNotExist(err) {
		t.Fatalf("failed validation must not leave a successful patch result: %v", err)
	}
}

type fakeAgent struct {
	results []agent.Result
	Starts  int
}

func (f *fakeAgent) Probe(context.Context) (agent.Capabilities, error) {
	return agent.Capabilities{}, nil
}

func (f *fakeAgent) Start(ctx context.Context, task agent.Task, workspace string, policy agent.Policy) (agent.Session, error) {
	f.Starts++
	idx := f.Starts - 1
	res := agent.Result{Success: true}
	if idx < len(f.results) {
		res = f.results[idx]
	}
	return &fakeSession{result: res}, nil
}

type fakeSession struct {
	result agent.Result
}

func (s *fakeSession) ID() string                 { return "fake-session" }
func (s *fakeSession) Events() <-chan agent.Event { return closedEventChan() }
func (s *fakeSession) Wait() agent.Result         { return s.result }
func (s *fakeSession) Cancel() error              { return nil }

func closedEventChan() <-chan agent.Event {
	ch := make(chan agent.Event)
	close(ch)
	return ch
}

type fakeValidator struct {
	results []Validation
	calls   int
}

func (v *fakeValidator) Validate(ctx context.Context, workspace string, commands []ValidationCommand) ([]Validation, error) {
	out := make([]Validation, 0, len(commands))
	if len(commands) == 0 {
		commands = []ValidationCommand{{}}
	}
	for range commands {
		res := Validation{ExitCode: 0}
		if v.calls < len(v.results) {
			res = v.results[v.calls]
		}
		v.calls++
		out = append(out, res)
	}
	return out, nil
}

func taskWithMaxRounds(n int, workspace, outDir string) RunTask {
	return RunTask{
		MaxRepairRounds: n,
		Workspace:       workspace,
		OutDir:          outDir,
		ValidationCommands: []ValidationCommand{
			{Program: "true", Args: nil, TimeoutSeconds: 5},
		},
	}
}

func TestSubmoduleGitlinkIsRejected(t *testing.T) {
	workspace := t.TempDir()
	outDir := filepath.Join(workspace, "out")
	agent := &fakeAgent{results: []agent.Result{{Success: true}}}
	validator := &fakeValidator{results: []Validation{{ExitCode: 0}}}
	git := &fakeGit{
		status: []byte("M  vendor/lib\x00"),
		patch:  []byte("diff --git a/vendor/lib b/vendor/lib\nindex 123..456 160000\n--- a/vendor/lib\n+++ b/vendor/lib\n@@ -1 +1 @@\n-Subproject commit aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n+Subproject commit bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n"),
		gitlinks: map[string]struct{}{"vendor/lib": {}},
	}
	result := NewRun(agent, validator, artifact.NewBuilder()).Execute(context.Background(), RunTask{
		MaxRepairRounds:    0, // normalize to default 2, but validation passes first round
		Workspace:          workspace,
		OutDir:             outDir,
		ValidationCommands: []ValidationCommand{{Program: "true", TimeoutSeconds: 5}},
		Git:                git,
	})
	// MaxRepairRounds 0 becomes default 2; validation passes on first try so finishSuccess runs.
	if result.Code != "SUBMODULE_CHANGE" {
		t.Fatalf("code = %s, err=%v", result.Code, result.Err)
	}
	if _, err := os.Stat(artifact.PatchPath(outDir)); !os.IsNotExist(err) {
		t.Fatalf("submodule rejection must not leave change.patch: %v", err)
	}
}

func TestGitmodulesPathIsRejected(t *testing.T) {
	workspace := t.TempDir()
	outDir := filepath.Join(workspace, "out")
	agent := &fakeAgent{results: []agent.Result{{Success: true}}}
	validator := &fakeValidator{results: []Validation{{ExitCode: 0}}}
	git := &fakeGit{
		status: []byte("M  .gitmodules\x00"),
		patch:  []byte("diff --git a/.gitmodules b/.gitmodules\n"),
	}
	result := NewRun(agent, validator, artifact.NewBuilder()).Execute(context.Background(), RunTask{
		Workspace:          workspace,
		OutDir:             outDir,
		ValidationCommands: []ValidationCommand{{Program: "true", TimeoutSeconds: 5}},
		Git:                git,
	})
	if result.Code != "SUBMODULE_CHANGE" {
		t.Fatalf("code = %s", result.Code)
	}
}

func TestParseGitlinkPaths(t *testing.T) {
	raw := []byte("160000 abcdef0123456789abcdef0123456789abcdef0 0\tvendor/lib\x00100644 abcdef0123456789abcdef0123456789abcdef1 0\tsrc/A.java\x00")
	got := parseGitlinkPaths(raw)
	if _, ok := got["vendor/lib"]; !ok {
		t.Fatalf("expected vendor/lib gitlink, got %#v", got)
	}
	if _, ok := got["src/A.java"]; ok {
		t.Fatalf("regular file must not be gitlink")
	}
}

type fakeGit struct {
	status   []byte
	patch    []byte
	gitlinks map[string]struct{}
}

func (f *fakeGit) StatusPorcelain(context.Context, string) ([]byte, error) { return f.status, nil }
func (f *fakeGit) DiffBinary(context.Context, string) ([]byte, error)      { return f.patch, nil }
func (f *fakeGit) DiffCheck(context.Context, string) error                 { return nil }
func (f *fakeGit) GitlinkPaths(context.Context, string) (map[string]struct{}, error) {
	if f.gitlinks == nil {
		return map[string]struct{}{}, nil
	}
	return f.gitlinks, nil
}
