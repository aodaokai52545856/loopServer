//go:build windows

package enrollment

import (
	"fmt"
	"os"
	"path/filepath"
	"syscall"
	"unsafe"
)

const cryptProtectLocalMachine = 0x4

type dataBlob struct {
	cbData uint32
	pbData *byte
}

func defaultStateDir() (string, error) {
	programData := os.Getenv("ProgramData")
	if programData == "" {
		return "", fmt.Errorf("ProgramData is not set")
	}
	return filepath.Join(programData, "repair-node"), nil
}

func protectKeyMaterial(plaintext []byte) ([]byte, error) {
	var inBlob dataBlob
	if len(plaintext) > 0 {
		inBlob.cbData = uint32(len(plaintext))
		inBlob.pbData = &plaintext[0]
	}
	var outBlob dataBlob
	crypt32 := syscall.NewLazyDLL("crypt32.dll")
	proc := crypt32.NewProc("CryptProtectData")
	r1, _, err := proc.Call(
		uintptr(unsafe.Pointer(&inBlob)),
		0,
		0,
		0,
		0,
		uintptr(cryptProtectLocalMachine),
		uintptr(unsafe.Pointer(&outBlob)),
	)
	if r1 == 0 {
		return nil, fmt.Errorf("CryptProtectData: %w", err)
	}
	defer localFree(unsafe.Pointer(outBlob.pbData))

	out := make([]byte, outBlob.cbData)
	copy(out, unsafe.Slice(outBlob.pbData, outBlob.cbData))
	return out, nil
}

func localFree(ptr unsafe.Pointer) {
	if ptr == nil {
		return
	}
	kernel32 := syscall.NewLazyDLL("kernel32.dll")
	proc := kernel32.NewProc("LocalFree")
	_, _, _ = proc.Call(uintptr(ptr))
}
