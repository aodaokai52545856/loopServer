package heartbeat

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestLoopSendsImmediatelyThenEveryFifteenSeconds(t *testing.T) {
	clock := newFakeClock(time.Date(2026, 7, 18, 8, 0, 0, 0, time.UTC))
	transport := &fakeTransport{responses: []Response{{DesiredRevision: 2, Config: &Config{Concurrency: 4}}}}
	loop := NewLoop(transport, clock, &fakeApplier{})
	loop.jitter = 0
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go loop.Run(ctx)
	transport.waitCalls(t, 1)
	clock.Advance(14 * time.Second)
	transport.assertCalls(t, 1)
	clock.Advance(time.Second)
	transport.waitCalls(t, 2)
	cancel()
}

func TestJitteredIntervalStaysWithinTenPercent(t *testing.T) {
	base := 15 * time.Second
	min := time.Duration(float64(base) * 0.9)
	max := time.Duration(float64(base) * 1.1)
	for i := 0; i < 200; i++ {
		got := JitteredInterval(base, 0.1)
		if got < min || got > max {
			t.Fatalf("jittered=%v outside [%v,%v]", got, min, max)
		}
	}
	if JitteredInterval(base, 0) != base {
		t.Fatal("zero fraction must return base")
	}
}

func TestLoopRetriesHTTPFailureWithBackoffWithoutStopping(t *testing.T) {
	clock := newFakeClock(time.Date(2026, 7, 18, 8, 0, 0, 0, time.UTC))
	transport := &fakeTransport{failUntil: 2, responses: []Response{{DesiredRevision: 1}}}
	loop := NewLoop(transport, clock, &fakeApplier{})
	loop.jitter = 0
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go loop.Run(ctx)

	transport.waitCalls(t, 1)
	waitFor(t, func() bool { return clock.recordedWait() == time.Second }, "first backoff 1s")
	clock.Advance(time.Second)
	transport.waitCalls(t, 2)
	waitFor(t, func() bool { return clock.recordedWait() == 2*time.Second }, "second backoff 2s")
	clock.Advance(2 * time.Second)
	transport.waitCalls(t, 3)
	waitFor(t, func() bool { return clock.recordedWait() == 15*time.Second }, "recovered 15s wait")
	if loop.lastError != nil {
		t.Fatalf("lastError after success = %v", *loop.lastError)
	}
	cancel()
}

func waitFor(t *testing.T, ok func() bool, label string) {
	t.Helper()
	deadline := time.After(2 * time.Second)
	for {
		if ok() {
			return
		}
		select {
		case <-deadline:
			t.Fatalf("timed out waiting for %s", label)
		case <-time.After(5 * time.Millisecond):
		}
	}
}

func TestLoopKeepsOldRevisionAndRedactsApplyError(t *testing.T) {
	clock := newFakeClock(time.Date(2026, 7, 18, 8, 0, 0, 0, time.UTC))
	transport := &fakeTransport{responses: []Response{{
		DesiredRevision: 3,
		Config:          &Config{Concurrency: 4},
	}}}
	applier := &fakeApplier{err: errors.New("open /secret/path/config.toml: access denied token=glrt-secret")}
	loop := NewLoop(transport, clock, applier)
	loop.jitter = 0
	loop.appliedRevision = 1
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go loop.Run(ctx)
	transport.waitCalls(t, 1)
	cancel()

	if loop.appliedRevision != 1 {
		t.Fatalf("appliedRevision = %d, want 1", loop.appliedRevision)
	}
	if loop.lastError == nil || *loop.lastError != errCodeApply {
		t.Fatalf("lastError = %v, want %s", loop.lastError, errCodeApply)
	}
	if loop.lastError != nil && strings.Contains(*loop.lastError, "glrt-secret") {
		t.Fatal("secret leaked into lastError")
	}
	if loop.lastError != nil && strings.Contains(*loop.lastError, "/secret/") {
		t.Fatal("path leaked into lastError")
	}
}

func TestClientPostsHeartbeatAndDecodesDesiredConfig(t *testing.T) {
	var gotPath string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		if r.Method != http.MethodPost {
			t.Fatalf("method = %s", r.Method)
		}
		body, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(body), `"appliedRevision":1`) {
			t.Fatalf("body = %s", body)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"desiredRevision":2,"config":{"concurrency":4,"allowedProjects":["a"],"drain":false}}`))
	}))
	defer server.Close()

	client := NewClient(server.URL, "node-1", server.Client())
	resp, err := client.Heartbeat(context.Background(), Request{
		ObservedAt:      time.Date(2026, 7, 18, 8, 0, 0, 0, time.UTC),
		AppliedRevision: 1,
		Runner:          Runner{Status: "online", Version: "18.2.0"},
		Slots:           Slots{Active: 0, Limit: 2},
		Resources:       Resources{},
		Tools:           Tools{OS: "linux", Arch: "amd64"},
		ActiveAttemptIds: []string{},
	})
	if err != nil {
		t.Fatal(err)
	}
	if gotPath != "/api/node/v1/nodes/node-1/heartbeat" {
		t.Fatalf("path = %s", gotPath)
	}
	if resp.DesiredRevision != 2 || resp.Config == nil || resp.Config.Concurrency != 4 {
		t.Fatalf("resp = %+v", resp)
	}
}

func TestRedactErrorMapsHTTPAndTransport(t *testing.T) {
	if got := redactError(&HTTPError{StatusCode: 503, Body: "token=secret"}); got != errCodeHTTP {
		t.Fatalf("http = %s", got)
	}
	if got := redactError(errors.New("dial tcp 10.0.0.1:443: connection refused")); got != errCodeTransport {
		t.Fatalf("transport = %s", got)
	}
}

type fakeClock struct {
	mu       sync.Mutex
	now      time.Time
	timers   []fakeTimer
	lastWait time.Duration
}

type fakeTimer struct {
	when time.Time
	ch   chan time.Time
}

func newFakeClock(now time.Time) *fakeClock {
	return &fakeClock{now: now}
}

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *fakeClock) After(d time.Duration) <-chan time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.lastWait = d
	ch := make(chan time.Time, 1)
	when := c.now.Add(d)
	c.timers = append(c.timers, fakeTimer{when: when, ch: ch})
	return ch
}

func (c *fakeClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
	remaining := c.timers[:0]
	for _, timer := range c.timers {
		if !c.now.Before(timer.when) {
			timer.ch <- c.now
		} else {
			remaining = append(remaining, timer)
		}
	}
	c.timers = remaining
}

func (c *fakeClock) recordedWait() time.Duration {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.lastWait
}

type fakeTransport struct {
	mu        sync.Mutex
	calls     int
	responses []Response
	failUntil int
	payloads  []Request
}

func (t *fakeTransport) Heartbeat(ctx context.Context, req Request) (Response, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.calls++
	t.payloads = append(t.payloads, req)
	if t.failUntil > 0 && t.calls <= t.failUntil {
		return Response{}, &HTTPError{StatusCode: 503, Body: "upstream token=secret"}
	}
	idx := t.calls - 1
	if t.failUntil > 0 {
		idx = t.calls - t.failUntil - 1
	}
	if idx < 0 {
		idx = 0
	}
	if idx >= len(t.responses) {
		idx = len(t.responses) - 1
	}
	if idx < 0 {
		return Response{}, nil
	}
	return t.responses[idx], nil
}

func (t *fakeTransport) waitCalls(tt *testing.T, n int) {
	tt.Helper()
	deadline := time.After(2 * time.Second)
	for {
		t.mu.Lock()
		got := t.calls
		t.mu.Unlock()
		if got >= n {
			return
		}
		select {
		case <-deadline:
			tt.Fatalf("want %d calls, got %d", n, got)
		case <-time.After(5 * time.Millisecond):
		}
	}
}

func (t *fakeTransport) assertCalls(tt *testing.T, n int) {
	tt.Helper()
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.calls != n {
		tt.Fatalf("calls = %d, want %d", t.calls, n)
	}
}

type fakeApplier struct {
	err     error
	applied []Config
}

func (a *fakeApplier) Apply(cfg Config) error {
	if a.err != nil {
		return a.err
	}
	a.applied = append(a.applied, cfg)
	return nil
}
