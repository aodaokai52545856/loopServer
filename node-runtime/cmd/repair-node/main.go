package main

import (
	"os"

	"company.internal/loop-engine/node-runtime/internal/version"
)

func main() {
	os.Exit(version.Command(os.Args[1:], os.Stdout, os.Stderr))
}
