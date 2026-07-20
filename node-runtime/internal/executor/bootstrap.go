package executor

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"company.internal/loop-engine/node-runtime/internal/credentials"
	"company.internal/loop-engine/node-runtime/internal/workspace"
)

const (
	maxTaskPackageBytes = 1 << 20 // 1 MiB
	maxAttachmentBytes  = 20 << 20
	fetchDepth          = 50
	redactedRemoteURL   = "no-push://redacted"
)

var (
	sha1Pattern = regexp.MustCompile(`^[0-9a-f]{6,40}$`)
	uuidPattern = regexp.MustCompile(`(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
)

// Git prepares a read-only detached checkout and exposes clone-time environment.
type Git interface {
	// Prepare runs init/fetch/checkout/redact/credential cleanup for baseSHA.
	Prepare(ctx context.Context, dir, repoURL, baseSHA string, env map[string]string) (head string, err error)
	// Env returns the ambient environment available during clone (may include CI/GitLab secrets).
	Env() map[string]string
}

// Attachment is one task-package attachment referenced by control-plane proxy URL.
type Attachment struct {
	Name        string
	ContentType string
	URL         string
	SHA256      string
	Size        int64
}

// Callback hosts event and attachment APIs for the attempt.
type Callback struct {
	BaseURL    string
	EventsPath string
}

// TaskPackage is the validated v1 task package used to bootstrap a workspace.
type TaskPackage struct {
	Protocol      string
	TaskID        string
	AttemptID     string
	NodeID        string
	BaseSHA       string
	WorkRoot      string
	RepositoryURL string
	Callback      Callback
	Attachments   []Attachment
	TaskToken     string
	HTTPClient    *http.Client
}

// BootstrapRequest starts an attempt on the control plane.
type BootstrapRequest struct {
	TaskID        string `json:"taskId"`
	ReservationID string `json:"reservationId"`
	PipelineID    int64  `json:"pipelineId"`
	JobID         int64  `json:"jobId"`
}

type bootstrapAPIResponse struct {
	AttemptID   string          `json:"attemptId"`
	TaskToken   string          `json:"taskToken"`
	TaskPackage json.RawMessage `json:"taskPackage"`
}

// Client fetches a short-lived task package using the node device identity transport.
type Client struct {
	BaseURL    string
	HTTPClient *http.Client
}

// FetchTaskPackage POSTs /api/node/v1/attempts/bootstrap and validates the package envelope.
func (c *Client) FetchTaskPackage(ctx context.Context, req BootstrapRequest) (token string, pkg TaskPackage, err error) {
	if c == nil || c.BaseURL == "" {
		return "", TaskPackage{}, fmt.Errorf("bootstrap client base URL required")
	}
	client := c.HTTPClient
	if client == nil {
		client = http.DefaultClient
	}
	body, err := json.Marshal(req)
	if err != nil {
		return "", TaskPackage{}, err
	}
	endpoint := strings.TrimRight(c.BaseURL, "/") + "/api/node/v1/attempts/bootstrap"
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return "", TaskPackage{}, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	resp, err := client.Do(httpReq)
	if err != nil {
		return "", TaskPackage{}, err
	}
	defer resp.Body.Close()
	limited := io.LimitReader(resp.Body, maxTaskPackageBytes+1)
	raw, err := io.ReadAll(limited)
	if err != nil {
		return "", TaskPackage{}, err
	}
	if len(raw) > maxTaskPackageBytes {
		return "", TaskPackage{}, fmt.Errorf("bootstrap response exceeds 1 MiB")
	}
	if resp.StatusCode != http.StatusOK {
		return "", TaskPackage{}, fmt.Errorf("bootstrap HTTP %d", resp.StatusCode)
	}
	var parsed bootstrapAPIResponse
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return "", TaskPackage{}, err
	}
	if len(parsed.TaskPackage) > maxTaskPackageBytes {
		return "", TaskPackage{}, fmt.Errorf("task-package.json exceeds 1 MiB")
	}
	pkg, err = ParseTaskPackage(parsed.TaskPackage)
	if err != nil {
		return "", TaskPackage{}, err
	}
	if parsed.AttemptID != "" && pkg.AttemptID != "" && parsed.AttemptID != pkg.AttemptID {
		return "", TaskPackage{}, fmt.Errorf("attemptId mismatch between envelope and task package")
	}
	pkg.TaskToken = parsed.TaskToken
	return parsed.TaskToken, pkg, nil
}

type wirePackage struct {
	Protocol  string `json:"protocol"`
	TaskID    string `json:"taskId"`
	AttemptID string `json:"attemptId"`
	NodeID    string `json:"nodeId"`
	BaseSHA   string `json:"baseSha"`
	Project   struct {
		RepositoryURL string `json:"repositoryUrl"`
	} `json:"project"`
	Issue struct {
		Attachments []Attachment `json:"attachments"`
	} `json:"issue"`
	Callback Callback `json:"callback"`
}

// ParseTaskPackage validates size, protocol, IDs, callback host and canonical Base SHA.
func ParseTaskPackage(raw []byte) (TaskPackage, error) {
	if len(raw) > maxTaskPackageBytes {
		return TaskPackage{}, fmt.Errorf("task-package.json exceeds 1 MiB")
	}
	var wire wirePackage
	if err := json.Unmarshal(raw, &wire); err != nil {
		return TaskPackage{}, err
	}
	pkg := TaskPackage{
		Protocol:      wire.Protocol,
		TaskID:        wire.TaskID,
		AttemptID:     wire.AttemptID,
		NodeID:        wire.NodeID,
		BaseSHA:       strings.ToLower(wire.BaseSHA),
		RepositoryURL: wire.Project.RepositoryURL,
		Callback:      wire.Callback,
		Attachments:   wire.Issue.Attachments,
	}
	if err := ValidateTaskPackage(pkg); err != nil {
		return TaskPackage{}, err
	}
	return pkg, nil
}

// ValidateTaskPackage checks protocol v1, IDs, callback host and Base SHA shape.
func ValidateTaskPackage(pkg TaskPackage) error {
	if pkg.Protocol != "v1" {
		return fmt.Errorf("unsupported protocol %q", pkg.Protocol)
	}
	for _, id := range []struct {
		name  string
		value string
	}{
		{"taskId", pkg.TaskID},
		{"attemptId", pkg.AttemptID},
		{"nodeId", pkg.NodeID},
	} {
		if !uuidPattern.MatchString(id.value) {
			return fmt.Errorf("invalid %s", id.name)
		}
	}
	if !sha1Pattern.MatchString(pkg.BaseSHA) {
		return fmt.Errorf("baseSha must be canonical SHA-1 hex")
	}
	if pkg.RepositoryURL == "" {
		return fmt.Errorf("repositoryUrl required")
	}
	callbackURL, err := url.Parse(pkg.Callback.BaseURL)
	if err != nil || callbackURL.Host == "" || (callbackURL.Scheme != "http" && callbackURL.Scheme != "https") {
		return fmt.Errorf("invalid callback baseUrl")
	}
	if pkg.Callback.EventsPath == "" || !strings.HasPrefix(pkg.Callback.EventsPath, "/api/node/v1/attempts/") {
		return fmt.Errorf("invalid callback eventsPath")
	}
	for _, attachment := range pkg.Attachments {
		if err := validateAttachmentURL(attachment.URL, callbackURL); err != nil {
			return err
		}
		if attachment.SHA256 == "" || len(attachment.SHA256) != 64 {
			return fmt.Errorf("attachment sha256 required")
		}
	}
	return nil
}

func validateAttachmentURL(raw string, callback *url.URL) error {
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return fmt.Errorf("invalid attachment url")
	}
	if !strings.EqualFold(u.Host, callback.Host) || u.Scheme != callback.Scheme {
		return fmt.Errorf("callback-host mismatch for attachment url")
	}
	return nil
}

// Run validates the package, creates an isolated workspace, clones read-only, downloads
// attachments and builds the allowlisted OpenCode environment.
func Run(ctx context.Context, pkg TaskPackage, git Git) (*workspace.Workspace, error) {
	if git == nil {
		return nil, fmt.Errorf("git implementation required")
	}
	if err := ValidateTaskPackage(pkg); err != nil {
		return nil, err
	}
	workRoot := pkg.WorkRoot
	if workRoot == "" {
		return nil, fmt.Errorf("workRoot required")
	}
	dir, err := workspace.Create(workRoot, pkg.TaskID, pkg.AttemptID)
	if err != nil {
		return nil, err
	}

	cloneEnv := map[string]string{}
	if git.Env() != nil {
		for k, v := range git.Env() {
			cloneEnv[k] = v
		}
	}
	head, err := git.Prepare(ctx, dir, pkg.RepositoryURL, pkg.BaseSHA, cloneEnv)
	if err != nil {
		return nil, err
	}
	head = strings.ToLower(strings.TrimSpace(head))
	if head != pkg.BaseSHA && !strings.HasPrefix(head, pkg.BaseSHA) && !strings.HasPrefix(pkg.BaseSHA, head) {
		return nil, fmt.Errorf("baseSha mismatch: package=%s head=%s", pkg.BaseSHA, head)
	}
	if len(pkg.BaseSHA) == 40 && head != pkg.BaseSHA {
		return nil, fmt.Errorf("baseSha mismatch: package=%s head=%s", pkg.BaseSHA, head)
	}

	attachmentDir := filepath.Join(filepath.Dir(dir), "input", "attachments")
	if err := os.MkdirAll(attachmentDir, 0o755); err != nil {
		return nil, err
	}
	paths, err := downloadAttachments(ctx, pkg, attachmentDir)
	if err != nil {
		return nil, err
	}

	agentEnv := credentials.FilterAgentEnv(cloneEnv)
	return &workspace.Workspace{
		Root:            dir,
		BaseSHA:         pkg.BaseSHA,
		AgentEnv:        agentEnv,
		AttachmentPaths: paths,
	}, nil
}

func downloadAttachments(ctx context.Context, pkg TaskPackage, destDir string) ([]string, error) {
	if len(pkg.Attachments) == 0 {
		return nil, nil
	}
	client := pkg.HTTPClient
	if client == nil {
		client = &http.Client{}
	}
	callbackURL, err := url.Parse(pkg.Callback.BaseURL)
	if err != nil {
		return nil, err
	}
	// Reject redirects that leave the callback host.
	baseClient := *client
	baseClient.CheckRedirect = func(req *http.Request, via []*http.Request) error {
		if req.URL.Host != "" && !strings.EqualFold(req.URL.Host, callbackURL.Host) {
			return fmt.Errorf("attachment redirect host mismatch")
		}
		if len(via) >= 10 {
			return fmt.Errorf("too many attachment redirects")
		}
		return nil
	}
	client = &baseClient

	paths := make([]string, 0, len(pkg.Attachments))
	for _, attachment := range pkg.Attachments {
		if err := validateAttachmentURL(attachment.URL, callbackURL); err != nil {
			return nil, err
		}
		id, err := attachmentIDFromURL(attachment.URL)
		if err != nil {
			return nil, err
		}
		path := filepath.Join(destDir, id)
		if err := fetchAttachment(ctx, client, pkg.TaskToken, attachment, path); err != nil {
			return nil, err
		}
		paths = append(paths, path)
	}
	return paths, nil
}

func attachmentIDFromURL(raw string) (string, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return "", err
	}
	parts := strings.Split(strings.Trim(u.Path, "/"), "/")
	if len(parts) == 0 {
		return "", fmt.Errorf("attachment url missing id")
	}
	id := parts[len(parts)-1]
	if !uuidPattern.MatchString(id) {
		return "", fmt.Errorf("attachment filename must be attachment UUID")
	}
	return strings.ToLower(id), nil
}

func fetchAttachment(ctx context.Context, client *http.Client, token string, attachment Attachment, dest string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, attachment.URL, nil)
	if err != nil {
		return err
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("attachment HTTP %d", resp.StatusCode)
	}
	limited := io.LimitReader(resp.Body, maxAttachmentBytes+1)
	data, err := io.ReadAll(limited)
	if err != nil {
		return err
	}
	if len(data) > maxAttachmentBytes {
		return fmt.Errorf("attachment exceeds 20 MiB")
	}
	if attachment.Size > 0 && int64(len(data)) != attachment.Size {
		return fmt.Errorf("attachment size mismatch")
	}
	sum := sha256.Sum256(data)
	got := hex.EncodeToString(sum[:])
	if !strings.EqualFold(got, attachment.SHA256) {
		return fmt.Errorf("attachment sha256 mismatch")
	}
	tmp := dest + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, dest)
}

// RealGit implements Git with argument-vector git commands (no shell).
type RealGit struct {
	Runner func(ctx context.Context, dir string, args []string, env map[string]string) error
	env    map[string]string
}

// Env returns clone-time environment.
func (g *RealGit) Env() map[string]string {
	if g.env != nil {
		return g.env
	}
	out := make(map[string]string)
	for _, entry := range os.Environ() {
		for i := 0; i < len(entry); i++ {
			if entry[i] == '=' {
				out[entry[:i]] = entry[i+1:]
				break
			}
		}
	}
	return out
}

// Prepare initializes a detached read-only checkout and redacts remotes/credentials.
func (g *RealGit) Prepare(ctx context.Context, dir, repoURL, baseSHA string, env map[string]string) (string, error) {
	run := g.Runner
	if run == nil {
		return "", fmt.Errorf("git runner required")
	}
	g.env = env
	steps := [][]string{
		{"init"},
		{"remote", "add", "origin", repoURL},
		{"fetch", "--depth", fmt.Sprintf("%d", fetchDepth), "origin", baseSHA},
		{"checkout", "--detach", "FETCH_HEAD"},
		{"remote", "set-url", "origin", redactedRemoteURL},
		{"config", "--unset-all", "credential.helper"},
		{"config", "--unset-all", "http.extraHeader"},
	}
	for _, args := range steps {
		if err := run(ctx, dir, args, env); err != nil {
			// credential.helper may be absent; ignore unset failures for cleanup keys.
			if args[0] == "config" && args[1] == "--unset-all" {
				continue
			}
			return "", err
		}
	}
	_ = removeAskpassFiles(dir, env)
	return baseSHA, nil
}

func removeAskpassFiles(dir string, env map[string]string) error {
	candidates := []string{}
	if v := env["GIT_ASKPASS"]; v != "" {
		candidates = append(candidates, v)
	}
	candidates = append(candidates,
		filepath.Join(dir, "git-askpass.sh"),
		filepath.Join(dir, "git-askpass.bat"),
		filepath.Join(dir, ".askpass"),
	)
	for _, path := range candidates {
		if path == "" {
			continue
		}
		_ = os.Remove(path)
	}
	return nil
}
