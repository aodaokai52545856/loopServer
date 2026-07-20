package probe

import (
	"bytes"
	"context"
	"os/exec"
	"strings"
	"time"
)

const probeTimeout = 3 * time.Second

type ToolCommand struct {
	Name    string
	Program string
	Args    []string
}

var toolCommands = []ToolCommand{
	{Name: "git", Program: "git", Args: []string{"--version"}},
	{Name: "java", Program: "java", Args: []string{"-version"}},
	{Name: "maven", Program: "mvn", Args: []string{"--version"}},
	{Name: "node", Program: "node", Args: []string{"--version"}},
	{Name: "pnpm", Program: "pnpm", Args: []string{"--version"}},
	{Name: "opencode", Program: "opencode", Args: []string{"--version"}},
	{Name: "docker", Program: "docker", Args: []string{"version", "--format", "{{.Server.Version}}"}},
}

// Result is one bounded tool probe outcome.
type Result struct {
	Name    string
	Version string
	OK      bool
}

// ProbeAll runs each known tool command with a 3-second timeout.
func ProbeAll(ctx context.Context) []Result {
	out := make([]Result, 0, len(toolCommands))
	for _, cmd := range toolCommands {
		out = append(out, Probe(ctx, cmd))
	}
	return out
}

// Probe runs a single tool command without a shell.
func Probe(ctx context.Context, tool ToolCommand) Result {
	ctx, cancel := context.WithTimeout(ctx, probeTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, tool.Program, tool.Args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	combined := strings.TrimSpace(stdout.String() + "\n" + stderr.String())
	if err != nil {
		return Result{Name: tool.Name, OK: false}
	}
	return Result{Name: tool.Name, Version: firstLine(combined), OK: true}
}

// Versions maps successful probe results by tool name.
func Versions(results []Result) map[string]string {
	out := make(map[string]string, len(results))
	for _, r := range results {
		if r.OK && r.Version != "" {
			out[r.Name] = r.Version
		}
	}
	return out
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
