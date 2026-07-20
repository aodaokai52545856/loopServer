package artifact

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	manifestFileName = "result-manifest.json"
	patchFileName    = "change.patch"
	testResultsDir   = "test-results"
)

// ChangedFile is one path recorded in the result manifest.
type ChangedFile struct {
	Path   string `json:"path"`
	Status string `json:"status"`
}

// ValidationResult is one validation command outcome in the manifest.
type ValidationResult struct {
	Program    string   `json:"program"`
	Args       []string `json:"args"`
	ExitCode   int      `json:"exitCode"`
	DurationMs int64    `json:"durationMs"`
}

// EventLogRef points at the local JSONL event spool included in the Artifact.
type EventLogRef struct {
	Path    string `json:"path"`
	SHA256  string `json:"sha256"`
	LastSeq int64  `json:"lastSeq"`
}

// Manifest is the v1 Job result descriptor.
type Manifest struct {
	Protocol          string             `json:"protocol"`
	TaskID            string             `json:"taskId"`
	AttemptID         string             `json:"attemptId"`
	NodeID            string             `json:"nodeId"`
	BaseSHA           string             `json:"baseSha"`
	PatchSHA256       string             `json:"patchSha256,omitempty"`
	PatchBytes        int64              `json:"patchBytes,omitempty"`
	ChangedFiles      []ChangedFile      `json:"changedFiles,omitempty"`
	ValidationResults []ValidationResult `json:"validationResults"`
	EventLog          EventLogRef        `json:"eventLog"`
	StartedAt         time.Time          `json:"startedAt"`
	FinishedAt        time.Time          `json:"finishedAt"`
	Outcome           string             `json:"outcome"`
	Code              string             `json:"code,omitempty"`
}

// BuildInput carries workspace diffs and metadata used to emit Artifacts.
type BuildInput struct {
	Workspace         string
	OutDir            string
	TaskID            string
	AttemptID         string
	NodeID            string
	BaseSHA           string
	Patch             []byte
	ChangedFiles      []ChangedFile
	ValidationResults []ValidationResult
	EventLog          EventLogRef
	StartedAt         time.Time
	FinishedAt        time.Time
	TestReportPaths   []string
	MaxChangedFiles   int
	MaxPatchBytes     int64
	ForbiddenPaths    []string
}

// Builder writes patch, test evidence and result-manifest.json under out/.
type Builder struct{}

// NewBuilder constructs an Artifact builder.
func NewBuilder() *Builder {
	return &Builder{}
}

// WriteFailedManifest writes a non-success manifest and never creates change.patch.
func (b *Builder) WriteFailedManifest(outDir string, m Manifest) error {
	if err := os.MkdirAll(outDir, 0o755); err != nil {
		return err
	}
	m.Protocol = "v1"
	if m.Outcome == "" || m.Outcome == "SUCCEEDED" {
		m.Outcome = "FAILED"
	}
	m.PatchSHA256 = ""
	m.PatchBytes = 0
	m.ChangedFiles = nil
	// Ensure a prior successful patch cannot linger beside a failed outcome.
	_ = os.Remove(filepath.Join(outDir, patchFileName))
	return writeManifest(outDir, m)
}

// BuildSuccess validates patch policy, writes change.patch / test-results / manifest.
func (b *Builder) BuildSuccess(in BuildInput) (Manifest, error) {
	if err := os.MkdirAll(in.OutDir, 0o755); err != nil {
		return Manifest{}, err
	}
	if len(in.Patch) == 0 {
		return Manifest{}, fmt.Errorf("empty patch")
	}
	if in.MaxPatchBytes > 0 && int64(len(in.Patch)) > in.MaxPatchBytes {
		return Manifest{}, fmt.Errorf("patch exceeds maxPatchBytes")
	}
	if in.MaxChangedFiles > 0 && len(in.ChangedFiles) > in.MaxChangedFiles {
		return Manifest{}, fmt.Errorf("changed files exceed maxChangedFiles")
	}
	for _, cf := range in.ChangedFiles {
		if forbidden(cf.Path, in.ForbiddenPaths) {
			return Manifest{}, fmt.Errorf("forbidden path: %s", cf.Path)
		}
		if err := rejectEscapingSymlink(in.Workspace, cf.Path); err != nil {
			return Manifest{}, err
		}
	}

	patchPath := filepath.Join(in.OutDir, patchFileName)
	if err := os.WriteFile(patchPath, in.Patch, 0o644); err != nil {
		return Manifest{}, err
	}
	sum := sha256.Sum256(in.Patch)
	if err := copyTestReports(in.OutDir, in.TestReportPaths); err != nil {
		return Manifest{}, err
	}

	m := Manifest{
		Protocol:          "v1",
		TaskID:            in.TaskID,
		AttemptID:         in.AttemptID,
		NodeID:            in.NodeID,
		BaseSHA:           in.BaseSHA,
		PatchSHA256:       hex.EncodeToString(sum[:]),
		PatchBytes:        int64(len(in.Patch)),
		ChangedFiles:      in.ChangedFiles,
		ValidationResults: in.ValidationResults,
		EventLog:          in.EventLog,
		StartedAt:         in.StartedAt.UTC(),
		FinishedAt:        in.FinishedAt.UTC(),
		Outcome:           "SUCCEEDED",
	}
	if err := writeManifest(in.OutDir, m); err != nil {
		return Manifest{}, err
	}
	return m, nil
}

func writeManifest(outDir string, m Manifest) error {
	raw, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	raw = append(raw, '\n')
	tmp := filepath.Join(outDir, manifestFileName+".tmp")
	if err := os.WriteFile(tmp, raw, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, filepath.Join(outDir, manifestFileName))
}

func copyTestReports(outDir string, paths []string) error {
	destRoot := filepath.Join(outDir, testResultsDir)
	if err := os.MkdirAll(destRoot, 0o755); err != nil {
		return err
	}
	for _, src := range paths {
		if src == "" {
			continue
		}
		base := filepath.Base(src)
		dest := filepath.Join(destRoot, base)
		if err := copyFile(src, dest); err != nil {
			return err
		}
	}
	return nil
}

func copyFile(src, dest string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dest, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return err
	}
	defer out.Close()
	_, err = io.Copy(out, in)
	return err
}

func forbidden(path string, patterns []string) bool {
	cleaned := filepath.ToSlash(path)
	for _, pattern := range patterns {
		p := filepath.ToSlash(pattern)
		if matchForbidden(cleaned, p) {
			return true
		}
	}
	return false
}

func matchForbidden(path, pattern string) bool {
	if pattern == "" {
		return false
	}
	if strings.HasSuffix(pattern, "/**") {
		prefix := strings.TrimSuffix(pattern, "/**")
		return path == prefix || strings.HasPrefix(path, prefix+"/")
	}
	ok, err := filepath.Match(pattern, path)
	if err == nil && ok {
		return true
	}
	ok, err = filepath.Match(pattern, filepath.Base(path))
	return err == nil && ok
}

func rejectEscapingSymlink(workspace, rel string) error {
	full := filepath.Join(workspace, filepath.FromSlash(rel))
	info, err := os.Lstat(full)
	if err != nil {
		// Deleted/missing paths are still represented in the patch; skip.
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	if info.Mode()&os.ModeSymlink == 0 {
		return nil
	}
	target, err := os.Readlink(full)
	if err != nil {
		return err
	}
	if !filepath.IsAbs(target) {
		target = filepath.Join(filepath.Dir(full), target)
	}
	resolved, err := filepath.Abs(target)
	if err != nil {
		return err
	}
	root, err := filepath.Abs(workspace)
	if err != nil {
		return err
	}
	sep := string(os.PathSeparator)
	if resolved != root && !strings.HasPrefix(resolved, root+sep) {
		return fmt.Errorf("symlink escapes repository: %s", rel)
	}
	return nil
}

// ReadManifest loads result-manifest.json from outDir when present.
func ReadManifest(outDir string) (Manifest, error) {
	raw, err := os.ReadFile(filepath.Join(outDir, manifestFileName))
	if err != nil {
		return Manifest{}, err
	}
	var m Manifest
	if err := json.Unmarshal(raw, &m); err != nil {
		return Manifest{}, err
	}
	return m, nil
}

// PatchPath returns the out/change.patch path.
func PatchPath(outDir string) string {
	return filepath.Join(outDir, patchFileName)
}
