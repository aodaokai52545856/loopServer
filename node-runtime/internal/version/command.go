package version

import (
	"encoding/json"
	"fmt"
	"io"
)

func Command(args []string, out, errOut io.Writer) int {
	if len(args) != 2 || args[0] != "version" || args[1] != "--json" {
		fmt.Fprintln(errOut, "usage: <command> version --json")
		return 2
	}
	if err := json.NewEncoder(out).Encode(Current()); err != nil {
		fmt.Fprintln(errOut, err)
		return 1
	}
	return 0
}
