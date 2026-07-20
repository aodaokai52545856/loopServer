//go:build windows

package runner

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
)

// windowsService manages SCM services through golang.org/x/sys/windows/svc
// for a dedicated local service account.
type windowsService struct {
	names   []string
	account string
	binDir  string
}

// DefaultService returns the Windows SCM-backed service adapter.
func DefaultService() Service {
	return &windowsService{
		names:   []string{"repair-node", "gitlab-runner"},
		account: `NT AUTHORITY\LocalService`,
	}
}

func (s *windowsService) Install(ctx context.Context) error {
	_ = ctx
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("connect scm: %w", err)
	}
	defer m.Disconnect()

	for _, name := range s.names {
		if existing, err := m.OpenService(name); err == nil {
			existing.Close()
			continue
		}
		binPath, err := s.binaryPath(name)
		if err != nil {
			return err
		}
		cfg := mgr.Config{
			DisplayName:      name,
			StartType:        mgr.StartAutomatic,
			ServiceStartName: s.account,
		}
		service, err := m.CreateService(name, binPath, cfg)
		if err != nil {
			return fmt.Errorf("create service %s: %w", name, err)
		}
		service.Close()
	}
	return nil
}

func (s *windowsService) Start(ctx context.Context) error {
	_ = ctx
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("connect scm: %w", err)
	}
	defer m.Disconnect()

	for _, name := range s.names {
		service, err := m.OpenService(name)
		if err != nil {
			return fmt.Errorf("open service %s: %w", name, err)
		}
		err = service.Start()
		service.Close()
		if err != nil {
			return fmt.Errorf("start service %s: %w", name, err)
		}
	}
	return nil
}

func (s *windowsService) Restart(ctx context.Context) error {
	_ = ctx
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("connect scm: %w", err)
	}
	defer m.Disconnect()

	for _, name := range s.names {
		service, err := m.OpenService(name)
		if err != nil {
			return fmt.Errorf("open service %s: %w", name, err)
		}
		_, _ = service.Control(svc.Stop)
		deadline := time.Now().Add(10 * time.Second)
		for time.Now().Before(deadline) {
			status, qerr := service.Query()
			if qerr == nil && status.State == svc.Stopped {
				break
			}
			time.Sleep(200 * time.Millisecond)
		}
		err = service.Start()
		service.Close()
		if err != nil {
			return fmt.Errorf("restart service %s: %w", name, err)
		}
	}
	return nil
}

func (s *windowsService) binaryPath(name string) (string, error) {
	switch name {
	case "gitlab-runner":
		if path, err := exec.LookPath("gitlab-runner.exe"); err == nil {
			return path, nil
		}
		if path, err := exec.LookPath("gitlab-runner"); err == nil {
			return path, nil
		}
		if s.binDir != "" {
			return filepath.Join(s.binDir, "gitlab-runner.exe"), nil
		}
		return "", fmt.Errorf("gitlab-runner binary not found; run repair-node doctor")
	case "repair-node":
		exe, err := os.Executable()
		if err != nil {
			return "", err
		}
		return exe, nil
	default:
		return "", fmt.Errorf("unknown service %s", name)
	}
}
