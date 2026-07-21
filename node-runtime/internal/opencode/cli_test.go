package opencode

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"company.internal/loop-engine/node-runtime/internal/agent"
)

func TestCLIMapsOpenCodeJSONToInternalEvents(t *testing.T) {
	runner := fakeProcessFromFile(t, "testdata/run-success.jsonl")
	cli := NewCLI(runner, "opencode")
	session, err := cli.Start(context.Background(), agent.Task{Prompt: "fix issue"}, t.TempDir(), agent.Policy{})
	if err != nil {
		t.Fatal(err)
	}
	var types []string
	for event := range session.Events() {
		types = append(types, event.Type)
	}
	want := []string{"agent.started", "agent.message", "tool.started", "tool.finished", "agent.finished"}
	if !reflect.DeepEqual(types, want) {
		t.Fatalf("types = %#v, want %#v", types, want)
	}
	result := session.Wait()
	if !result.Success {
		t.Fatalf("result = %+v", result)
	}
	if session.ID() != "ses_success" {
		t.Fatalf("session id = %q", session.ID())
	}
}

func TestCLIMalformedEventReturnsProtocolError(t *testing.T) {
	runner := &scriptedRunner{stdout: "{not-json\n"}
	cli := NewCLI(runner, "opencode")
	session, err := cli.Start(context.Background(), agent.Task{Prompt: "x"}, t.TempDir(), agent.Policy{})
	if err != nil {
		t.Fatal(err)
	}
	for range session.Events() {
	}
	result := session.Wait()
	if result.Success {
		t.Fatal("expected failure")
	}
	if result.Code != ProtocolErrorCode {
		t.Fatalf("code = %q", result.Code)
	}
	if result.Err == nil || !strings.Contains(result.Err.Error(), ProtocolErrorCode) {
		t.Fatalf("err = %v", result.Err)
	}
}

func TestCLIOversizedEventReturnsProtocolError(t *testing.T) {
	huge := strings.Repeat("a", maxEventBytes+8)
	runner := &scriptedRunner{stdout: `{"type":"text","sessionID":"ses_big","part":{"text":"` + huge + `"}}` + "\n"}
	cli := NewCLI(runner, "opencode")
	session, err := cli.Start(context.Background(), agent.Task{Prompt: "x"}, t.TempDir(), agent.Policy{})
	if err != nil {
		t.Fatal(err)
	}
	for range session.Events() {
	}
	result := session.Wait()
	if result.Success {
		t.Fatal("expected failure")
	}
	if result.Code != ProtocolErrorCode {
		t.Fatalf("code = %q", result.Code)
	}
}

func TestCLICancelInterruptsThenKills(t *testing.T) {
	proc := newBlockingProcess()
	runner := &scriptedRunner{proc: proc}
	cli := NewCLI(runner, "opencode")
	cli.cancelGrace = 20 * time.Millisecond
	session, err := cli.Start(context.Background(), agent.Task{Prompt: "x"}, t.TempDir(), agent.Policy{})
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan agent.Result, 1)
	go func() { done <- session.Wait() }()
	if err := session.Cancel(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Wait did not return after Cancel")
	}
	if !proc.interrupted.Load() {
		t.Fatal("expected Interrupt")
	}
	if !proc.killed.Load() {
		t.Fatal("expected Kill after grace period")
	}
}

func TestCLIUnknownEventBecomesAgentRawWithoutSecrets(t *testing.T) {
	runner := &scriptedRunner{stdout: `{"type":"custom.metric","sessionID":"ses_1","token":"secret","part":{"password":"x","ok":true}}` + "\n"}
	cli := NewCLI(runner, "opencode")
	session, err := cli.Start(context.Background(), agent.Task{Prompt: "x"}, t.TempDir(), agent.Policy{})
	if err != nil {
		t.Fatal(err)
	}
	var events []agent.Event
	for event := range session.Events() {
		events = append(events, event)
	}
	if len(events) != 1 || events[0].Type != eventAgentRaw {
		t.Fatalf("events = %#v", events)
	}
	if _, ok := events[0].Payload["token"]; ok {
		t.Fatal("token should be scrubbed")
	}
	part, _ := events[0].Payload["part"].(map[string]any)
	if part == nil {
		t.Fatal("expected part payload")
	}
	if _, ok := part["password"]; ok {
		t.Fatal("password should be scrubbed")
	}
	if part["ok"] != true {
		t.Fatalf("ok = %#v", part["ok"])
	}
}

func TestProbeRejectsVersionOutsideAllowlist(t *testing.T) {
	runner := &scriptedRunner{stdout: "1.0.180\n"}
	cli := NewCLI(runner, "opencode")
	cli.AllowedVersions = []string{"1.0.200"}
	_, err := cli.Probe(context.Background())
	if err == nil || !strings.Contains(err.Error(), "allowlist") {
		t.Fatalf("err = %v", err)
	}
}

func fakeProcessFromFile(t *testing.T, rel string) Runner {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(".", rel))
	if err != nil {
		t.Fatal(err)
	}
	return &scriptedRunner{stdout: string(data)}
}

type scriptedRunner struct {
	stdout   string
	stderr   string
	proc     *blockingProcess
	lastArgs []string
	lastEnv  map[string]string
}

func (r *scriptedRunner) Start(_ context.Context, _ string, args []string, env map[string]string) (Process, error) {
	r.lastArgs = append([]string(nil), args...)
	r.lastEnv = env
	if r.proc != nil {
		return r.proc, nil
	}
	return &staticProcess{
		stdout: io.NopCloser(strings.NewReader(r.stdout)),
		stderr: io.NopCloser(strings.NewReader(r.stderr)),
	}, nil
}

type staticProcess struct {
	stdout io.ReadCloser
	stderr io.ReadCloser
}

func (p *staticProcess) Stdout() io.Reader { return p.stdout }
func (p *staticProcess) Stderr() io.Reader { return p.stderr }
func (p *staticProcess) Wait() error {
	// Streams are owned by session.consume (stdout decode + stderr copy).
	// Do not drain them here or race detector will flag concurrent Reader use.
	return nil
}
func (p *staticProcess) Interrupt() error { return nil }
func (p *staticProcess) Kill() error      { return nil }

type blockingProcess struct {
	stdoutReader *io.PipeReader
	stdoutWriter *io.PipeWriter
	stderr       *bytes.Reader
	interrupt    chan struct{}
	killed       atomicBool
	interrupted  atomicBool
	waitOnce     sync.Once
	waitErr      error
}

func newBlockingProcess() *blockingProcess {
	pr, pw := io.Pipe()
	return &blockingProcess{
		stdoutReader: pr,
		stdoutWriter: pw,
		stderr:       bytes.NewReader(nil),
		interrupt:    make(chan struct{}),
	}
}

func (p *blockingProcess) Stdout() io.Reader { return p.stdoutReader }
func (p *blockingProcess) Stderr() io.Reader { return p.stderr }

func (p *blockingProcess) Wait() error {
	p.waitOnce.Do(func() {
		<-p.interrupt
		_ = p.stdoutWriter.Close()
		if p.killed.Load() {
			p.waitErr = errors.New("killed")
			return
		}
		p.waitErr = errors.New("interrupted")
	})
	return p.waitErr
}

func (p *blockingProcess) Interrupt() error {
	// Graceful interrupt alone must not finish the process; Kill does.
	p.interrupted.Store(true)
	return nil
}

func (p *blockingProcess) Kill() error {
	p.killed.Store(true)
	select {
	case <-p.interrupt:
	default:
		close(p.interrupt)
	}
	_ = p.stdoutWriter.Close()
	return nil
}

type atomicBool struct {
	mu sync.Mutex
	v  bool
}

func (a *atomicBool) Store(v bool) {
	a.mu.Lock()
	a.v = v
	a.mu.Unlock()
}

func (a *atomicBool) Load() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.v
}
