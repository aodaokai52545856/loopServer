package heartbeat

import (
	"context"
	"errors"
	"math/rand"
	"time"
)

const (
	baseInterval     = 15 * time.Second
	maxBackoff       = 15 * time.Second
	errCodeHTTP      = "HEARTBEAT_HTTP_FAILED"
	errCodeApply     = "CONFIG_APPLY_FAILED"
	errCodeTransport = "HEARTBEAT_TRANSPORT_FAILED"
)

// Transport sends heartbeats to the control plane.
type Transport interface {
	Heartbeat(ctx context.Context, req Request) (Response, error)
}

// Clock abstracts time for deterministic tests.
type Clock interface {
	Now() time.Time
	After(d time.Duration) <-chan time.Time
}

// Applier applies a desired config (temp file + Runner update).
type Applier interface {
	Apply(cfg Config) error
}

// Loop sends an immediate heartbeat then repeats on a 15s ±10% schedule.
type Loop struct {
	transport Transport
	clock     Clock
	applier   Applier

	// jitter is the ± fraction of baseInterval; 0 disables jitter (tests).
	jitter float64

	appliedRevision int64
	lastError       *string
	tools           Tools
	runner          Runner
	slots           Slots
	resources       Resources
	activeAttempts  []string
	backoff         time.Duration
}

// NewLoop constructs a heartbeat loop with ±10% jitter enabled.
func NewLoop(transport Transport, clock Clock, applier Applier) *Loop {
	return &Loop{
		transport: transport,
		clock:     clock,
		applier:   applier,
		jitter:    0.1,
		runner:    Runner{Status: "unknown", Version: "unknown"},
		slots:     Slots{Active: 0, Limit: 1},
		resources: Resources{},
		tools: Tools{
			OS:   "unknown",
			Arch: "unknown",
		},
		activeAttempts: []string{},
	}
}

// SetTools updates the tools payload included in heartbeats.
func (l *Loop) SetTools(tools Tools) { l.tools = tools }

// SetRunner updates runner status reported on heartbeats.
func (l *Loop) SetRunner(runner Runner) { l.runner = runner }

// SetSlots updates concurrency slot metrics.
func (l *Loop) SetSlots(slots Slots) { l.slots = slots }

// SetResources updates resource metrics.
func (l *Loop) SetResources(resources Resources) { l.resources = resources }

// Run blocks until ctx is cancelled, sending heartbeats forever.
func (l *Loop) Run(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}
		l.tick(ctx)
		wait := l.nextWait()
		select {
		case <-ctx.Done():
			return
		case <-l.clock.After(wait):
		}
	}
}

func (l *Loop) tick(ctx context.Context) {
	req := Request{
		ObservedAt:       l.clock.Now().UTC(),
		AppliedRevision:  l.appliedRevision,
		Runner:           l.runner,
		Slots:            l.slots,
		Resources:        l.resources,
		Tools:            l.tools,
		ActiveAttemptIds: l.activeAttempts,
		LastError:        l.lastError,
	}
	resp, err := l.transport.Heartbeat(ctx, req)
	if err != nil {
		l.lastError = strPtr(redactError(err))
		l.backoff = nextBackoff(l.backoff)
		return
	}
	l.backoff = 0
	if resp.Config != nil && resp.DesiredRevision > l.appliedRevision {
		if err := l.applier.Apply(*resp.Config); err != nil {
			l.lastError = strPtr(errCodeApply)
			return
		}
		l.appliedRevision = resp.DesiredRevision
	}
	l.lastError = nil
}

func (l *Loop) nextWait() time.Duration {
	if l.backoff > 0 {
		return l.backoff
	}
	return JitteredInterval(baseInterval, l.jitter)
}

// JitteredInterval returns base ± fraction (inclusive of lower bound).
func JitteredInterval(base time.Duration, fraction float64) time.Duration {
	if fraction <= 0 || base <= 0 {
		return base
	}
	span := float64(base) * fraction
	offset := (rand.Float64()*2 - 1) * span
	out := time.Duration(float64(base) + offset)
	if out < 0 {
		return 0
	}
	return out
}

func nextBackoff(prev time.Duration) time.Duration {
	if prev <= 0 {
		return time.Second
	}
	next := prev * 2
	if next > maxBackoff {
		return maxBackoff
	}
	return next
}

func redactError(err error) string {
	var httpErr *HTTPError
	if errors.As(err, &httpErr) {
		return errCodeHTTP
	}
	return errCodeTransport
}

func strPtr(s string) *string { return &s }

type realClock struct{}

func (realClock) Now() time.Time                         { return time.Now() }
func (realClock) After(d time.Duration) <-chan time.Time { return time.After(d) }

// RealClock returns the wall-clock implementation.
func RealClock() Clock { return realClock{} }
