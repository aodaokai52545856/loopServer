package opencode

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"sync"
	"time"

	"company.internal/loop-engine/node-runtime/internal/agent"
	"company.internal/loop-engine/node-runtime/internal/credentials"
)

const (
	maxEventBytes  = 4 << 20 // 4 MiB
	stderrRingSize = 256 << 10
)

// Process is a running OpenCode child without a shell.
type Process interface {
	Stdout() io.Reader
	Stderr() io.Reader
	Wait() error
	Interrupt() error
	Kill() error
}

// Runner starts OpenCode (or a test double) directly.
type Runner interface {
	Start(ctx context.Context, binary string, args []string, env map[string]string) (Process, error)
}

// CLI adapts OpenCode CLI JSON output to the stable agent.Agent interface.
type CLI struct {
	runner          Runner
	binary          string
	cancelGrace     time.Duration
	AllowedVersions []string
}

// NewCLI constructs an OpenCode CLI adapter.
func NewCLI(runner Runner, binary string) *CLI {
	return &CLI{
		runner:      runner,
		binary:      binary,
		cancelGrace: 10 * time.Second,
	}
}

var _ agent.Agent = (*CLI)(nil)

// Probe runs `opencode --version` without a shell and rejects versions outside
// the configured allowlist when AllowedVersions is non-empty.
func (c *CLI) Probe(ctx context.Context) (agent.Capabilities, error) {
	return c.probe(ctx, nil)
}

func (c *CLI) probe(ctx context.Context, env map[string]string) (agent.Capabilities, error) {
	proc, err := c.runner.Start(ctx, c.binary, []string{"--version"}, credentials.FilterAgentEnv(env))
	if err != nil {
		return agent.Capabilities{}, err
	}
	var stdout bytes.Buffer
	_, _ = io.Copy(&stdout, io.LimitReader(proc.Stdout(), 64<<10))
	waitErr := proc.Wait()
	version := strings.TrimSpace(firstLine(stdout.String()))
	if waitErr != nil && version == "" {
		return agent.Capabilities{}, waitErr
	}
	allowlist := c.AllowedVersions
	if err := checkVersionAllowlist(version, allowlist); err != nil {
		return agent.Capabilities{}, err
	}
	return agent.Capabilities{Version: version}, nil
}

func (c *CLI) Start(ctx context.Context, task agent.Task, workspace string, policy agent.Policy) (agent.Session, error) {
	env := credentials.FilterAgentEnv(policy.Environment)
	args := []string{"run", "--format", "json", "--dir", workspace, task.Prompt}
	proc, err := c.runner.Start(ctx, c.binary, args, env)
	if err != nil {
		return nil, err
	}
	grace := c.cancelGrace
	if grace <= 0 {
		grace = 10 * time.Second
	}
	sess := &session{
		events:      make(chan agent.Event, 16),
		proc:        proc,
		cancelGrace: grace,
		done:        make(chan struct{}),
	}
	go sess.consume()
	return sess, nil
}

type session struct {
	id          string
	events      chan agent.Event
	proc        Process
	cancelGrace time.Duration

	mu     sync.Mutex
	result agent.Result
	done   chan struct{}

	cancelOnce sync.Once
}

func (s *session) ID() string { return s.id }

func (s *session) Events() <-chan agent.Event { return s.events }

func (s *session) Wait() agent.Result {
	<-s.done
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.result
}

func (s *session) Cancel() error {
	var err error
	s.cancelOnce.Do(func() {
		err = s.proc.Interrupt()
		timer := time.NewTimer(s.cancelGrace)
		defer timer.Stop()
		select {
		case <-s.done:
			return
		case <-timer.C:
			if killErr := s.proc.Kill(); killErr != nil && err == nil {
				err = killErr
			}
			<-s.done
		}
	})
	return err
}

func (s *session) consume() {
	defer close(s.done)
	defer close(s.events)

	stderrBuf := newRingBuffer(stderrRingSize)
	go func() {
		_, _ = io.Copy(stderrBuf, s.proc.Stderr())
	}()

	protocolErr := s.decodeStdout()
	waitErr := s.proc.Wait()

	s.mu.Lock()
	defer s.mu.Unlock()
	s.result.SessionID = s.id
	if protocolErr != nil {
		s.result.Success = false
		s.result.Code = ProtocolErrorCode
		s.result.Err = protocolErr
		return
	}
	if waitErr != nil {
		s.result.Success = false
		s.result.Err = waitErr
		if s.result.Code == "" {
			s.result.Code = "OPENCODE_EXIT_ERROR"
		}
		return
	}
	s.result.Success = true
}

func (s *session) decodeStdout() error {
	limited := &resetLimitedReader{R: s.proc.Stdout(), N: maxEventBytes + 1}
	dec := json.NewDecoder(limited)
	for {
		limited.N = maxEventBytes + 1
		var raw json.RawMessage
		err := dec.Decode(&raw)
		if errors.Is(err, io.EOF) {
			return nil
		}
		if err != nil {
			return fmt.Errorf("%s: %w", ProtocolErrorCode, err)
		}
		if limited.N <= 0 {
			return fmt.Errorf("%s: event exceeds %d bytes", ProtocolErrorCode, maxEventBytes)
		}
		upstream, err := parseUpstream(raw)
		if err != nil {
			return fmt.Errorf("%s: %w", ProtocolErrorCode, err)
		}
		if upstream.SessionID != "" && s.id == "" {
			s.id = upstream.SessionID
		}
		for _, mapped := range mapUpstream(upstream) {
			if mapped.SessionID != "" && s.id == "" {
				s.id = mapped.SessionID
			}
			s.events <- agent.Event{Type: mapped.Type, Payload: mapped.Payload}
		}
	}
}

type resetLimitedReader struct {
	R io.Reader
	N int64
}

func (l *resetLimitedReader) Read(p []byte) (int, error) {
	if l.N <= 0 {
		return 0, errors.New("opencode event size limit exceeded")
	}
	if int64(len(p)) > l.N {
		p = p[:l.N]
	}
	n, err := l.R.Read(p)
	l.N -= int64(n)
	return n, err
}

type ringBuffer struct {
	mu   sync.Mutex
	buf  []byte
	size int
}

func newRingBuffer(size int) *ringBuffer {
	return &ringBuffer{buf: make([]byte, 0, size), size: size}
}

func (r *ringBuffer) Write(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(p) >= r.size {
		r.buf = append(r.buf[:0], p[len(p)-r.size:]...)
		return len(p), nil
	}
	need := len(r.buf) + len(p) - r.size
	if need > 0 {
		r.buf = r.buf[need:]
	}
	r.buf = append(r.buf, p...)
	return len(p), nil
}

func (r *ringBuffer) Bytes() []byte {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]byte, len(r.buf))
	copy(out, r.buf)
	return out
}

func checkVersionAllowlist(version string, allowed []string) error {
	if len(allowed) == 0 {
		return nil
	}
	for _, candidate := range allowed {
		if version == candidate || strings.HasPrefix(version, candidate) {
			return nil
		}
	}
	return fmt.Errorf("opencode version %q not in allowlist", version)
}

func firstLine(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		return strings.TrimSpace(s[:i])
	}
	return s
}
