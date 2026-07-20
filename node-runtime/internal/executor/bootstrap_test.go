package executor_test

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	bootstrap "company.internal/loop-engine/node-runtime/internal/executor"
)

func TestBootstrapClonesThenRemovesGitLabCredentials(t *testing.T) {
	git := &fakeGit{head: "aabbcc", envSeen: map[string]string{"CI_JOB_TOKEN": "secret"}}
	ws, err := bootstrap.Run(context.Background(), taskPackage("aabbcc"), git)
	if err != nil {
		t.Fatal(err)
	}
	if ws.BaseSHA != "aabbcc" {
		t.Fatalf("base = %s", ws.BaseSHA)
	}
	for _, key := range []string{"CI_JOB_TOKEN", "GITLAB_TOKEN", "GIT_ASKPASS"} {
		if _, ok := ws.AgentEnv[key]; ok {
			t.Fatalf("agent inherited %s", key)
		}
	}
}

func TestRunRejectsWrongBaseSHA(t *testing.T) {
	git := &fakeGit{head: "dddddd", envSeen: map[string]string{"PATH": "/bin"}}
	_, err := bootstrap.Run(context.Background(), taskPackage("aabbcc"), git)
	if err == nil {
		t.Fatal("expected baseSha mismatch")
	}
	if !strings.Contains(err.Error(), "baseSha") {
		t.Fatalf("err=%v", err)
	}
}

func TestRunRejectsSymlinkEscape(t *testing.T) {
	root := t.TempDir()
	real := filepath.Join(root, "real")
	link := filepath.Join(root, "link")
	if err := os.Mkdir(real, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(real, link); err != nil {
		t.Skip("symlink not permitted:", err)
	}
	pkg := taskPackage("aabbcc")
	pkg.WorkRoot = link
	git := &fakeGit{head: "aabbcc", envSeen: map[string]string{"PATH": "/bin"}}
	_, err := bootstrap.Run(context.Background(), pkg, git)
	if err == nil {
		t.Fatal("expected symlink rejection")
	}
	if !strings.Contains(err.Error(), "symlink") {
		t.Fatalf("err=%v", err)
	}
}

func TestRunRejectsCallbackHostMismatch(t *testing.T) {
	pkg := taskPackage("aabbcc")
	pkg.Attachments = []bootstrap.Attachment{{
		Name:        "note.txt",
		ContentType: "text/plain",
		URL:         "https://evil.example/api/node/v1/attempts/" + pkg.AttemptID + "/attachments/11111111-1111-1111-1111-111111111111",
		SHA256:      strings.Repeat("a", 64),
	}}
	git := &fakeGit{head: "aabbcc", envSeen: map[string]string{"PATH": "/bin"}}
	_, err := bootstrap.Run(context.Background(), pkg, git)
	if err == nil {
		t.Fatal("expected callback-host mismatch")
	}
	if !strings.Contains(err.Error(), "callback-host") {
		t.Fatalf("err=%v", err)
	}
}

func TestRunDownloadsAttachmentsToUUIDFilenames(t *testing.T) {
	content := []byte("hello-attachment")
	sum := sha256.Sum256(content)
	digest := hex.EncodeToString(sum[:])
	attachmentID := "22222222-2222-2222-2222-222222222222"

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer task-token" {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		w.Write(content)
	}))
	defer server.Close()

	pkg := taskPackage("aabbcc")
	pkg.Callback.BaseURL = server.URL
	pkg.TaskToken = "task-token"
	pkg.HTTPClient = server.Client()
	pkg.Attachments = []bootstrap.Attachment{{
		Name:        "secret-name.txt",
		ContentType: "text/plain",
		URL:         server.URL + "/api/node/v1/attempts/" + pkg.AttemptID + "/attachments/" + attachmentID,
		SHA256:      digest,
		Size:        int64(len(content)),
	}}
	git := &fakeGit{
		head: "aabbcc",
		envSeen: map[string]string{
			"PATH":         "/bin",
			"CI_JOB_TOKEN": "secret",
			"GIT_ASKPASS":  "/tmp/askpass",
		},
	}
	ws, err := bootstrap.Run(context.Background(), pkg, git)
	if err != nil {
		t.Fatal(err)
	}
	if len(ws.AttachmentPaths) != 1 {
		t.Fatalf("attachments=%d", len(ws.AttachmentPaths))
	}
	if filepath.Base(ws.AttachmentPaths[0]) != attachmentID {
		t.Fatalf("filename=%s", ws.AttachmentPaths[0])
	}
	got, err := os.ReadFile(ws.AttachmentPaths[0])
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(content) {
		t.Fatalf("content=%q", got)
	}
	if _, ok := ws.AgentEnv["CI_JOB_TOKEN"]; ok {
		t.Fatal("task/clone secrets leaked into agent env")
	}
	if _, ok := ws.AgentEnv["GIT_ASKPASS"]; ok {
		t.Fatal("GIT_ASKPASS leaked into agent env")
	}
}

func TestValidateRejectsNonCanonicalBaseSHA(t *testing.T) {
	pkg := taskPackage("aabbcc")
	pkg.BaseSHA = "NOT-A-SHA"
	if err := bootstrap.ValidateTaskPackage(pkg); err == nil {
		t.Fatal("expected invalid baseSha")
	}
}

func taskPackage(baseSHA string) bootstrap.TaskPackage {
	workRoot, err := os.MkdirTemp("", "bootstrap-ws-*")
	if err != nil {
		panic(err)
	}
	return bootstrap.TaskPackage{
		Protocol:      "v1",
		TaskID:        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
		AttemptID:     "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
		NodeID:        "cccccccc-cccc-cccc-cccc-cccccccccccc",
		BaseSHA:       baseSHA,
		WorkRoot:      workRoot,
		RepositoryURL: "https://gitlab.internal/group/repo.git",
		Callback: bootstrap.Callback{
			BaseURL:    "https://control.example",
			EventsPath: "/api/node/v1/attempts/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb/events:batch",
		},
	}
}

type fakeGit struct {
	head    string
	envSeen map[string]string
	dir     string
}

func (f *fakeGit) Env() map[string]string {
	return f.envSeen
}

func (f *fakeGit) Prepare(ctx context.Context, dir, repoURL, baseSHA string, env map[string]string) (string, error) {
	_ = ctx
	_ = repoURL
	_ = baseSHA
	f.dir = dir
	if env != nil {
		if f.envSeen == nil {
			f.envSeen = map[string]string{}
		}
		for k, v := range env {
			f.envSeen[k] = v
		}
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	// Simulate irreversible credential cleanup markers.
	_ = os.WriteFile(filepath.Join(dir, ".git-remote"), []byte("no-push://redacted"), 0o600)
	return f.head, nil
}
