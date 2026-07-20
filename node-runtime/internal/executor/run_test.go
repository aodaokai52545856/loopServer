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
