package process

import (
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

const maxCaptureBytes = 10 << 20 // 10 MiB

// Spec runs a program with an argument vector (never a shell).
type Spec struct {
	Program    string
	Args       []string
	Dir        string
	Env        []string
	Timeout    time.Duration
	StdoutPath string
	StderrPath string
}

// Result is the terminal outcome of one process invocation.
type Result struct {
	ExitCode int
	TimedOut bool
	Duration time.Duration
	Err      error
}

// Run starts program+args directly, captures capped stdout/stderr, and kills the
// process tree on timeout or context cancel.
func Run(ctx context.Context, spec Spec) Result {
	start := time.Now()
	if spec.Program == "" {
		return Result{ExitCode: -1, Duration: time.Since(start), Err: fmt.Errorf("program required")}
	}
	if spec.Timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, spec.Timeout)
		defer cancel()
	}

	cmd := exec.CommandContext(ctx, spec.Program, spec.Args...)
	cmd.Dir = spec.Dir
	if len(spec.Env) > 0 {
		cmd.Env = spec.Env
	}
	configureProcessGroup(cmd)

	stdoutPath := spec.StdoutPath
	stderrPath := spec.StderrPath
	if stdoutPath == "" {
		stdoutPath = filepath.Join(os.TempDir(), fmt.Sprintf("repair-stdout-%d.log", os.Getpid()))
	}
	if stderrPath == "" {
		stderrPath = filepath.Join(os.TempDir(), fmt.Sprintf("repair-stderr-%d.log", os.Getpid()))
	}
	stdoutW, err := openCapped(stdoutPath, maxCaptureBytes)
	if err != nil {
		return Result{ExitCode: -1, Duration: time.Since(start), Err: err}
	}
	defer stdoutW.Close()
	stderrW, err := openCapped(stderrPath, maxCaptureBytes)
	if err != nil {
		return Result{ExitCode: -1, Duration: time.Since(start), Err: err}
	}
	defer stderrW.Close()
	cmd.Stdout = stdoutW
	cmd.Stderr = stderrW

	if err := cmd.Start(); err != nil {
		return Result{ExitCode: -1, Duration: time.Since(start), Err: err}
	}

	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()

	select {
	case err := <-done:
		code := 0
		if err != nil {
			if ee, ok := err.(*exec.ExitError); ok {
				code = ee.ExitCode()
			} else {
				return Result{ExitCode: -1, Duration: time.Since(start), Err: err}
			}
		}
		return Result{ExitCode: code, Duration: time.Since(start)}
	case <-ctx.Done():
		_ = killProcessTree(cmd)
		<-done
		timedOut := ctx.Err() == context.DeadlineExceeded
		return Result{
			ExitCode: -1,
			TimedOut: timedOut,
			Duration: time.Since(start),
			Err:      ctx.Err(),
		}
	}
}

type cappedWriter struct {
	f   *os.File
	max int64
}

func openCapped(path string, max int64) (*cappedWriter, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, err
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return nil, err
	}
	return &cappedWriter{f: f, max: max}, nil
}

func (w *cappedWriter) Write(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	info, err := w.f.Stat()
	if err != nil {
		return 0, err
	}
	size := info.Size()
	if size >= w.max {
		// Rotate: keep the trailing max/2 bytes, then append.
		if err := rotateKeepTail(w.f, w.max/2); err != nil {
			return 0, err
		}
		info, err = w.f.Stat()
		if err != nil {
			return 0, err
		}
		size = info.Size()
	}
	remaining := w.max - size
	if int64(len(p)) <= remaining {
		return w.f.Write(p)
	}
	// Write what fits, then rotate and write the rest.
	n, err := w.f.Write(p[:remaining])
	if err != nil {
		return n, err
	}
	if err := rotateKeepTail(w.f, w.max/2); err != nil {
		return n, err
	}
	m, err := w.f.Write(p[remaining:])
	return n + m, err
}

func (w *cappedWriter) Close() error {
	if w.f == nil {
		return nil
	}
	err := w.f.Close()
	w.f = nil
	return err
}

func rotateKeepTail(f *os.File, keep int64) error {
	info, err := f.Stat()
	if err != nil {
		return err
	}
	size := info.Size()
	if size <= keep {
		return nil
	}
	buf := make([]byte, keep)
	if _, err := f.ReadAt(buf, size-keep); err != nil && err != io.EOF {
		return err
	}
	if err := f.Truncate(0); err != nil {
		return err
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return err
	}
	_, err = f.Write(buf)
	return err
}

// ReadTailLines returns up to maxLines trailing non-empty-friendly lines from path.
func ReadTailLines(path string, maxLines int) ([]string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	if len(data) == 0 {
		return nil, nil
	}
	lines := splitLines(string(data))
	if len(lines) > maxLines {
		lines = lines[len(lines)-maxLines:]
	}
	return lines, nil
}

func splitLines(s string) []string {
	out := make([]string, 0, 64)
	start := 0
	for i := 0; i < len(s); i++ {
		if s[i] == '\n' {
			line := s[start:i]
			if len(line) > 0 && line[len(line)-1] == '\r' {
				line = line[:len(line)-1]
			}
			out = append(out, line)
			start = i + 1
		}
	}
	if start < len(s) {
		line := s[start:]
		if len(line) > 0 && line[len(line)-1] == '\r' {
			line = line[:len(line)-1]
		}
		out = append(out, line)
	}
	return out
}
