//go:build darwin

package runner

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
)

const (
	repairNodePlist   = "com.company.repair-node.plist"
	gitlabRunnerPlist = "com.gitlab.runner.plist"
)

// darwinService manages user LaunchAgent plists for the logged-in developer.
type darwinService struct {
	agentsDir string
	labels    []string
}

// DefaultService returns the macOS LaunchAgent-backed service adapter.
func DefaultService() Service {
	home, _ := os.UserHomeDir()
	return &darwinService{
		agentsDir: filepath.Join(home, "Library", "LaunchAgents"),
		labels:    []string{"com.company.repair-node", "com.gitlab.runner"},
	}
}

func (s *darwinService) Install(ctx context.Context) error {
	if err := os.MkdirAll(s.agentsDir, 0o755); err != nil {
		return err
	}
	for _, name := range []string{repairNodePlist, gitlabRunnerPlist} {
		path := filepath.Join(s.agentsDir, name)
		if _, err := os.Stat(path); err == nil {
			continue
		}
		// Placeholder plist path is expected to be provisioned by the installer;
		// Install only ensures the LaunchAgents directory exists.
		_ = path
	}
	_ = ctx
	return nil
}

func (s *darwinService) Start(ctx context.Context) error {
	for _, label := range s.labels {
		plist := filepath.Join(s.agentsDir, label+".plist")
		if err := runFixed(ctx, "launchctl", "load", "-w", plist); err != nil {
			// Missing plist is non-fatal during bootstrap; kickstart when loaded.
			_ = err
		}
		_ = runFixed(ctx, "launchctl", "kickstart", "-k", "gui/"+uid()+"/"+label)
	}
	return nil
}

func (s *darwinService) Restart(ctx context.Context) error {
	for _, label := range s.labels {
		_ = runFixed(ctx, "launchctl", "kickstart", "-k", "gui/"+uid()+"/"+label)
	}
	return nil
}

func runFixed(ctx context.Context, binary string, args ...string) error {
	cmd := exec.CommandContext(ctx, binary, args...)
	cmd.Env = sanitizedRunnerEnv()
	return cmd.Run()
}

func uid() string {
	return strconv.Itoa(os.Getuid())
}
