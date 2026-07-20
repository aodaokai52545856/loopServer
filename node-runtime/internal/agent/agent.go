package agent

import "context"

// Capabilities reports executor probe results for scheduling/profile checks.
type Capabilities struct {
	Version string
}

// Task is the prompt and metadata for one OpenCode session start.
type Task struct {
	Prompt string
}

// Policy controls the agent process environment and version allowlist.
type Policy struct {
	Environment     map[string]string
	AllowedVersions []string
}

// Event is a stable internal event independent of OpenCode wire shapes.
type Event struct {
	Type    string
	Payload map[string]any
}

// Result is the terminal outcome of a Session.
type Result struct {
	Success   bool
	Code      string
	Err       error
	SessionID string
}

// Agent starts and probes an AI coding session backend.
type Agent interface {
	Probe(context.Context) (Capabilities, error)
	Start(context.Context, Task, string, Policy) (Session, error)
}

// Session streams events until the backend exits or is cancelled.
type Session interface {
	ID() string
	Events() <-chan Event
	Wait() Result
	Cancel() error
}
