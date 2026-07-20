package workspace

import (
	"fmt"
	"os"
	"path/filepath"
)

// Workspace is an isolated repair attempt directory plus agent environment.
type Workspace struct {
	Root            string
	BaseSHA         string
	AgentEnv        map[string]string
	AttachmentPaths []string
}

// Create makes <workRoot>/<taskID>/<attemptID>, rejecting symlinks in any parent.
func Create(workRoot, taskID, attemptID string) (string, error) {
	if workRoot == "" || taskID == "" || attemptID == "" {
		return "", fmt.Errorf("workRoot, taskID and attemptID are required")
	}
	if err := rejectSymlinksInParents(workRoot); err != nil {
		return "", err
	}
	taskDir := filepath.Join(workRoot, taskID)
	if err := os.MkdirAll(taskDir, 0o755); err != nil {
		return "", err
	}
	if err := rejectSymlinksInParents(taskDir); err != nil {
		return "", err
	}
	attemptDir := filepath.Join(taskDir, attemptID)
	if err := os.MkdirAll(attemptDir, 0o755); err != nil {
		return "", err
	}
	if err := rejectSymlinksInParents(attemptDir); err != nil {
		return "", err
	}
	info, err := os.Lstat(attemptDir)
	if err != nil {
		return "", err
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return "", fmt.Errorf("workspace path is a symlink: %s", attemptDir)
	}
	return attemptDir, nil
}

func rejectSymlinksInParents(path string) error {
	cleaned := filepath.Clean(path)
	for {
		info, err := os.Lstat(cleaned)
		if err != nil {
			if os.IsNotExist(err) {
				parent := filepath.Dir(cleaned)
				if parent == cleaned {
					return nil
				}
				cleaned = parent
				continue
			}
			return err
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("symlink in workspace path: %s", cleaned)
		}
		parent := filepath.Dir(cleaned)
		if parent == cleaned {
			return nil
		}
		cleaned = parent
	}
}
