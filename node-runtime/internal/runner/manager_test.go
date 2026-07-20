package runner

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestConfigurePinsTagsAndConcurrency(t *testing.T) {
	dir := t.TempDir()
	runner := &fakeCommandRunner{}
	m := NewManager(dir, runner, fakeService{})
	err := m.Configure(Config{URL: "https://gitlab.internal", Token: "glrt-one-time",
		Tag: "repair-node-node-1", Concurrency: 4, BuildsDir: filepath.Join(dir, "builds")})
	if err != nil {
		t.Fatal(err)
	}
	config := mustRead(t, filepath.Join(dir, "config.toml"))
	assertContains(t, config, `concurrent = 4`)
	assertContains(t, config, `run_untagged = false`)
	assertContains(t, config, `tag_list = ["repair-node-node-1"]`)
}

func TestDoctorReportsInstallURLWhenBinaryAbsent(t *testing.T) {
	dir := t.TempDir()
	m := NewManager(dir, &fakeCommandRunner{}, fakeService{})
	m.binary = filepath.Join(dir, "missing-gitlab-runner")
	report := m.Doctor()
	if report.BinaryPresent {
		t.Fatal("expected binary absent")
	}
	if report.InstallURL != RunnerInstallURL {
		t.Fatalf("install_url=%q", report.InstallURL)
	}
	if report.ChecksumField != RunnerChecksumField {
		t.Fatalf("checksum_field=%q", report.ChecksumField)
	}
	var buf strings.Builder
	WriteDoctorReport(&buf, report)
	out := buf.String()
	assertContains(t, out, "gitlab-runner: absent")
	assertContains(t, out, "install_url="+RunnerInstallURL)
	assertContains(t, out, "checksum_field="+RunnerChecksumField)
}

type fakeCommandRunner struct {
	calls []commandCall
}

type commandCall struct {
	binary string
	args   []string
	env    []string
}

func (f *fakeCommandRunner) Run(ctx context.Context, binary string, args []string, env []string) error {
	_ = ctx
	copied := append([]string(nil), args...)
	f.calls = append(f.calls, commandCall{binary: binary, args: copied, env: append([]string(nil), env...)})
	return nil
}

type fakeService struct{}

func (fakeService) Install(ctx context.Context) error  { _ = ctx; return nil }
func (fakeService) Start(ctx context.Context) error    { _ = ctx; return nil }
func (fakeService) Restart(ctx context.Context) error  { _ = ctx; return nil }

func mustRead(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func assertContains(t *testing.T, haystack, needle string) {
	t.Helper()
	if !strings.Contains(haystack, needle) {
		t.Fatalf("expected %q in:\n%s", needle, haystack)
	}
}
