package runner

import (
	"context"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

const (
	defaultBinary = "gitlab-runner"
	// Official GitLab Runner download landing; doctor reports URL + checksum field only.
	RunnerInstallURL    = "https://docs.gitlab.com/runner/install/"
	RunnerChecksumField = "sha256"
)

// Config is the desired GitLab Runner registration and local pin settings.
type Config struct {
	URL         string
	Token       string
	Tag         string
	Concurrency int
	BuildsDir   string
}

// CommandRunner executes a program with an argument vector (never a shell).
type CommandRunner interface {
	Run(ctx context.Context, binary string, args []string, env []string) error
}

// Service manages OS-specific install/start/restart of Runner-related services.
type Service interface {
	Install(ctx context.Context) error
	Start(ctx context.Context) error
	Restart(ctx context.Context) error
}

// Manager configures a uniquely tagged GitLab Runner and pins concurrency.
type Manager struct {
	dir      string
	binary   string
	commands CommandRunner
	service  Service
}

// NewManager creates a Manager rooted at dir (config.toml location).
func NewManager(dir string, commands CommandRunner, service Service) *Manager {
	return &Manager{
		dir:      dir,
		binary:   defaultBinary,
		commands: commands,
		service:  service,
	}
}

// DoctorReport describes Runner binary presence and install guidance.
type DoctorReport struct {
	BinaryPresent bool
	InstallURL    string
	ChecksumField string
}

// Doctor reports expected install URL and checksum field when the binary is absent.
// It does not download the Runner.
func (m *Manager) Doctor() DoctorReport {
	report := DoctorReport{
		InstallURL:    RunnerInstallURL,
		ChecksumField: RunnerChecksumField,
	}
	if path, err := exec.LookPath(m.binary); err == nil {
		if st, err := os.Stat(path); err == nil && !st.IsDir() {
			report.BinaryPresent = true
			return report
		}
	}
	candidate := filepath.Join(m.dir, m.binary)
	if st, err := os.Stat(candidate); err == nil && !st.IsDir() {
		report.BinaryPresent = true
	}
	return report
}

// WriteDoctorReport prints Runner presence or the install URL and checksum field.
func WriteDoctorReport(w io.Writer, report DoctorReport) {
	if report.BinaryPresent {
		fmt.Fprintln(w, "gitlab-runner: present")
		return
	}
	fmt.Fprintf(w, "gitlab-runner: absent\ninstall_url=%s\nchecksum_field=%s\n",
		report.InstallURL, report.ChecksumField)
}

// Configure registers the Runner with a unique tag, clamps concurrency, and writes config.toml.
func (m *Manager) Configure(cfg Config) error {
	normalized, err := normalizeConfig(cfg)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(m.dir, 0o755); err != nil {
		return err
	}
	if err := os.MkdirAll(normalized.BuildsDir, 0o755); err != nil {
		return err
	}

	ctx := context.Background()
	configPath := filepath.Join(m.dir, "config.toml")
	args := []string{
		"register",
		"--non-interactive",
		"--url", normalized.URL,
		"--token", normalized.Token,
		"--executor", "shell",
		"--description", normalized.Tag,
		"--tag-list", normalized.Tag,
		"--locked=true",
		"--run-untagged=false",
		"--builds-dir", normalized.BuildsDir,
		"--config", configPath,
	}
	if err := m.commands.Run(ctx, m.binary, args, sanitizedRunnerEnv()); err != nil {
		return err
	}
	if err := writePinnedConfig(configPath, normalized); err != nil {
		return err
	}
	if err := m.service.Install(ctx); err != nil {
		return err
	}
	if err := m.service.Start(ctx); err != nil {
		return err
	}
	return nil
}

func normalizeConfig(cfg Config) (Config, error) {
	if strings.TrimSpace(cfg.URL) == "" {
		return Config{}, fmt.Errorf("runner url is required")
	}
	if strings.TrimSpace(cfg.Token) == "" {
		return Config{}, fmt.Errorf("runner token is required")
	}
	if strings.TrimSpace(cfg.Tag) == "" {
		return Config{}, fmt.Errorf("runner tag is required")
	}
	if strings.TrimSpace(cfg.BuildsDir) == "" {
		return Config{}, fmt.Errorf("builds dir is required")
	}
	buildsDir, err := filepath.Abs(cfg.BuildsDir)
	if err != nil {
		return Config{}, err
	}
	cfg.BuildsDir = buildsDir
	cfg.Concurrency = clampConcurrency(cfg.Concurrency)
	return cfg, nil
}

func clampConcurrency(n int) int {
	if n < 1 {
		return 1
	}
	if n > 10 {
		return 10
	}
	return n
}

func writePinnedConfig(path string, cfg Config) error {
	content := fmt.Sprintf(
		"concurrent = %d\n\n[[runners]]\n  name = %q\n  url = %q\n  executor = \"shell\"\n  builds_dir = %q\n  run_untagged = false\n  tag_list = [%q]\n",
		cfg.Concurrency,
		cfg.Tag,
		cfg.URL,
		cfg.BuildsDir,
		cfg.Tag,
	)
	return os.WriteFile(path, []byte(content), 0o600)
}

// sanitizedRunnerEnv returns a process environment with GitLab/CI/task credentials removed.
func sanitizedRunnerEnv() []string {
	env := os.Environ()
	out := make([]string, 0, len(env))
	for _, kv := range env {
		key, _, _ := strings.Cut(kv, "=")
		upper := strings.ToUpper(key)
		if strings.HasPrefix(upper, "CI_") ||
			strings.HasPrefix(upper, "GITLAB_") ||
			strings.HasPrefix(upper, "GL_") ||
			upper == "GITLAB_TOKEN" ||
			upper == "CI_JOB_TOKEN" ||
			upper == "TASK_TOKEN" ||
			upper == "REPAIR_TASK_TOKEN" {
			continue
		}
		out = append(out, kv)
	}
	return out
}

// ExecCommandRunner runs binaries via os/exec without a shell.
type ExecCommandRunner struct{}

func (ExecCommandRunner) Run(ctx context.Context, binary string, args []string, env []string) error {
	cmd := exec.CommandContext(ctx, binary, args...)
	if env != nil {
		cmd.Env = env
	}
	return cmd.Run()
}
