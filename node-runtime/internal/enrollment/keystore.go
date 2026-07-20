package enrollment

import (
	"crypto/ed25519"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"os"
	"path/filepath"
)

type FileKeyStore struct {
	dir string
}

func NewFileKeyStore(dir string) *FileKeyStore {
	return &FileKeyStore{dir: dir}
}

func DefaultStateDir() (string, error) {
	return defaultStateDir()
}

func (s *FileKeyStore) Save(privateKey ed25519.PrivateKey, state State) error {
	if err := os.MkdirAll(s.dir, 0o700); err != nil {
		return err
	}
	keyPEM, err := encodePrivateKeyPEM(privateKey)
	if err != nil {
		return err
	}
	protected, err := protectKeyMaterial(keyPEM)
	if err != nil {
		return err
	}
	if err := atomicWriteFile(filepath.Join(s.dir, "device.key"), protected, 0o600); err != nil {
		return err
	}
	raw, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	raw = append(raw, '\n')
	return atomicWriteFile(filepath.Join(s.dir, "state.json"), raw, 0o600)
}

func encodePrivateKeyPEM(privateKey ed25519.PrivateKey) ([]byte, error) {
	der, err := x509.MarshalPKCS8PrivateKey(privateKey)
	if err != nil {
		return nil, err
	}
	return pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), nil
}

func atomicWriteFile(path string, data []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+".tmp-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	cleanup := true
	defer func() {
		if cleanup {
			_ = os.Remove(tmpName)
		}
	}()
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Chmod(tmpName, mode); err != nil {
		return err
	}
	if err := os.Rename(tmpName, path); err != nil {
		if removeErr := os.Remove(path); removeErr != nil && !os.IsNotExist(removeErr) {
			return fmt.Errorf("replace %s: %w", path, err)
		}
		if err := os.Rename(tmpName, path); err != nil {
			return err
		}
	}
	cleanup = false
	return nil
}
