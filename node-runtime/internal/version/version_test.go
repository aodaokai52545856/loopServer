package version

import (
	"bytes"
	"strings"
	"testing"
)

func TestInfoHasProtocolVersion(t *testing.T) {
	info := Current()
	if info.Protocol != "v1" {
		t.Fatalf("protocol = %q", info.Protocol)
	}
	if info.Component == "" {
		t.Fatal("component is empty")
	}
}

func TestUnknownCommandReturnsUsage(t *testing.T) {
	var stdout, stderr bytes.Buffer
	if code := Command([]string{"bad"}, &stdout, &stderr); code != 2 {
		t.Fatalf("code = %d", code)
	}
	if !strings.Contains(stderr.String(), "usage:") {
		t.Fatal(stderr.String())
	}
}
