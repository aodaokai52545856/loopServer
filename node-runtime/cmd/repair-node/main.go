package main

import (
	"context"
	"flag"
	"fmt"
	"os"

	"company.internal/loop-engine/node-runtime/internal/enrollment"
	"company.internal/loop-engine/node-runtime/internal/version"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "join" {
		os.Exit(runJoin(os.Args[2:]))
	}
	os.Exit(version.Command(os.Args[1:], os.Stdout, os.Stderr))
}

func runJoin(args []string) int {
	fs := flag.NewFlagSet("join", flag.ContinueOnError)
	fs.SetOutput(os.Stderr)
	server := fs.String("server", "", "control plane base URL")
	code := fs.String("code", "", "one-time invite code")
	name := fs.String("name", "", "node display name")
	stateDir := fs.String("state-dir", "", "local identity directory")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *server == "" || *code == "" {
		fmt.Fprintln(os.Stderr, "usage: repair-node join --server <url> --code <code> [--name <name>] [--state-dir <dir>]")
		return 2
	}
	nodeName := *name
	if nodeName == "" {
		host, err := os.Hostname()
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		nodeName = host
	}
	dir := *stateDir
	if dir == "" {
		resolved, err := enrollment.DefaultStateDir()
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		dir = resolved
	}
	client := enrollment.NewClient(*server, enrollment.NewFileKeyStore(dir), nil)
	state, err := client.Join(context.Background(), enrollment.JoinRequest{Name: nodeName, Code: *code})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	fmt.Fprintf(os.Stdout, "joined node %s runnerTag=%s\n", state.NodeID, state.RunnerTag)
	return 0
}
