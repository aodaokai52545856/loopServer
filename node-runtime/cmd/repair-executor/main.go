package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"sync"

	"company.internal/loop-engine/node-runtime/internal/agent"
	"company.internal/loop-engine/node-runtime/internal/artifact"
	"company.internal/loop-engine/node-runtime/internal/credentials"
	"company.internal/loop-engine/node-runtime/internal/executor"
	"company.internal/loop-engine/node-runtime/internal/opencode"
	"company.internal/loop-engine/node-runtime/internal/version"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "execute" {
		os.Exit(runExecute(os.Args[2:]))
	}
	os.Exit(version.Command(os.Args[1:], os.Stdout, os.Stderr))
}

func runExecute(args []string) int {
	fs := flag.NewFlagSet("execute", flag.ContinueOnError)
	fs.SetOutput(os.Stderr)
	workspace := fs.String("workspace", "", "isolated attempt workspace")
	outDir := fs.String("out", "", "artifact output directory")
	prompt := fs.String("prompt", "", "initial OpenCode prompt")
	binary := fs.String("opencode", "opencode", "OpenCode CLI binary")
	maxRounds := fs.Int("max-repair-rounds", 2, "max validation feedback rounds (phase one <= 2)")
	program := fs.String("program", "", "validation program (argv[0])")
	timeout := fs.Int("timeout-seconds", 1200, "validation timeout")
	var valArgs multiFlag
	fs.Var(&valArgs, "arg", "validation program argument (repeatable)")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *workspace == "" || *program == "" {
		fmt.Fprintln(os.Stderr, "usage: repair-executor execute --workspace <dir> --program <bin> [--arg ...] [--out <dir>]")
		return 2
	}
	out := *outDir
	if out == "" {
		out = *workspace + string(os.PathSeparator) + "out"
	}
	agentEnv := credentials.FilterAgentEnvList(os.Environ())
	cli := opencode.NewCLI(osRunner{}, *binary)
	result := executor.NewRun(cli, executor.ProcessValidator{}, artifact.NewBuilder()).Execute(context.Background(), executor.RunTask{
		Workspace:       *workspace,
		OutDir:          out,
		Prompt:          *prompt,
		MaxRepairRounds: *maxRounds,
		Policy:          agent.Policy{Environment: agentEnv},
		ValidationCommands: []executor.ValidationCommand{{
			Program:        *program,
			Args:           append([]string(nil), valArgs...),
			TimeoutSeconds: *timeout,
		}},
	})
	if result.Code != "SUCCEEDED" {
		if result.Err != nil {
			fmt.Fprintln(os.Stderr, result.Err)
		}
		fmt.Fprintln(os.Stderr, result.Code)
		return 1
	}
	fmt.Println(result.Code)
	return 0
}

type multiFlag []string

func (m *multiFlag) String() string { return strings.Join(*m, ",") }
func (m *multiFlag) Set(v string) error {
	*m = append(*m, v)
	return nil
}

type osRunner struct{}

func (osRunner) Start(ctx context.Context, binary string, args []string, env map[string]string) (opencode.Process, error) {
	cmd := exec.CommandContext(ctx, binary, args...)
	// Always set Env from the already-filtered map. A nil/empty Env must not
	// fall back to inheriting CI_JOB_TOKEN / GitLab / task credentials.
	cmd.Env = envMapToSlice(env)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	return &osProcess{cmd: cmd, stdout: stdout, stderr: stderr}, nil
}

type osProcess struct {
	cmd    *exec.Cmd
	stdout io.ReadCloser
	stderr io.ReadCloser
	once   sync.Once
	waitErr error
}

func (p *osProcess) Stdout() io.Reader { return p.stdout }
func (p *osProcess) Stderr() io.Reader { return p.stderr }

func (p *osProcess) Wait() error {
	p.once.Do(func() {
		p.waitErr = p.cmd.Wait()
	})
	return p.waitErr
}

func (p *osProcess) Interrupt() error {
	if p.cmd.Process == nil {
		return nil
	}
	return p.cmd.Process.Signal(os.Interrupt)
}

func (p *osProcess) Kill() error {
	if p.cmd.Process == nil {
		return nil
	}
	return p.cmd.Process.Kill()
}

func envMapToSlice(env map[string]string) []string {
	out := make([]string, 0, len(env))
	for k, v := range env {
		out = append(out, k+"="+v)
	}
	return out
}
