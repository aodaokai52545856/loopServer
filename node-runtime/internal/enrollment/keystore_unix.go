//go:build unix

package enrollment

import (
	"os"
	"path/filepath"
)

func defaultStateDir() (string, error) {
	if os.Getuid() == 0 {
		return "/var/lib/repair-node", nil
	}
	if xdg := os.Getenv("XDG_STATE_HOME"); xdg != "" {
		return filepath.Join(xdg, "repair-node"), nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".local", "state", "repair-node"), nil
}

func protectKeyMaterial(plaintext []byte) ([]byte, error) {
	out := make([]byte, len(plaintext))
	copy(out, plaintext)
	return out, nil
}
