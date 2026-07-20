//go:build linux

package runner

import (
	"context"
	"os/exec"
)

// linuxService manages systemctl units for repair-node and gitlab-runner
// under the dedicated repair-node user.
type linuxService struct {
	units []string
}

// DefaultService returns the Linux systemctl-backed service adapter.
func DefaultService() Service {
	return &linuxService{units: []string{"repair-node.service", "gitlab-runner.service"}}
}

func (s *linuxService) Install(ctx context.Context) error {
	for _, unit := range s.units {
		if err := runFixed(ctx, "systemctl", "enable", unit); err != nil {
			return err
		}
	}
	return nil
}

func (s *linuxService) Start(ctx context.Context) error {
	for _, unit := range s.units {
		if err := runFixed(ctx, "systemctl", "start", unit); err != nil {
			return err
		}
	}
	return nil
}

func (s *linuxService) Restart(ctx context.Context) error {
	for _, unit := range s.units {
		if err := runFixed(ctx, "systemctl", "restart", unit); err != nil {
			return err
		}
	}
	return nil
}

func runFixed(ctx context.Context, binary string, args ...string) error {
	cmd := exec.CommandContext(ctx, binary, args...)
	cmd.Env = sanitizedRunnerEnv()
	return cmd.Run()
}
