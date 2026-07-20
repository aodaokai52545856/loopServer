package enrollment

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func TestJoinGeneratesKeyAndStoresReturnedIdentity(t *testing.T) {
	dir := t.TempDir()
	server := newEnrollmentServer(t, http.StatusCreated, enrollmentResponse("node-1"), false)
	client := NewClient(server.URL, NewFileKeyStore(dir), server.Client())

	got, err := client.Join(context.Background(), JoinRequest{Name: "alice-mac", Code: "invite"})
	if err != nil {
		t.Fatal(err)
	}
	if got.NodeID != "node-1" {
		t.Fatalf("node = %q", got.NodeID)
	}
	assertMode(t, filepath.Join(dir, "device.key"), 0o600)
	if bytes.Contains(mustRead(t, filepath.Join(dir, "state.json")), []byte("invite")) {
		t.Fatal("invite code persisted")
	}
	assertDeviceKeyProtected(t, filepath.Join(dir, "device.key"))
}

func TestJoinRejectsCertificateWithWrongPublicKey(t *testing.T) {
	dir := t.TempDir()
	server := newEnrollmentServer(t, http.StatusCreated, enrollmentResponse("node-2"), true)
	client := NewClient(server.URL, NewFileKeyStore(dir), server.Client())

	_, err := client.Join(context.Background(), JoinRequest{Name: "bob-win", Code: "invite-bad"})
	if err == nil {
		t.Fatal("expected certificate mismatch error")
	}
	if _, statErr := os.Stat(filepath.Join(dir, "state.json")); !os.IsNotExist(statErr) {
		t.Fatalf("state.json should not exist after failed join: %v", statErr)
	}
	if _, statErr := os.Stat(filepath.Join(dir, "device.key")); !os.IsNotExist(statErr) {
		t.Fatalf("device.key should not exist after failed join: %v", statErr)
	}
}

func TestSaveReplacesAtomicallyWithoutInviteOrTemps(t *testing.T) {
	dir := t.TempDir()
	store := NewFileKeyStore(dir)
	_, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	first := State{
		NodeID:          "node-a",
		Server:          "https://control.example",
		CertificatePEM:  "-----BEGIN CERTIFICATE-----\nA\n-----END CERTIFICATE-----\n",
		CAPEM:           "-----BEGIN CERTIFICATE-----\nCA\n-----END CERTIFICATE-----\n",
		RunnerTag:       "repair-node-node-a",
		AllowedProjects: []string{"alpha"},
	}
	if err := store.Save(key, first); err != nil {
		t.Fatal(err)
	}

	_, key2, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	second := first
	second.NodeID = "node-b"
	second.RunnerTag = "repair-node-node-b"
	second.AllowedProjects = []string{"beta"}
	if err := store.Save(key2, second); err != nil {
		t.Fatal(err)
	}

	raw := mustRead(t, filepath.Join(dir, "state.json"))
	if bytes.Contains(raw, []byte("invite")) {
		t.Fatal("invite code persisted")
	}
	var got State
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	if got.NodeID != "node-b" {
		t.Fatalf("node = %q", got.NodeID)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		name := entry.Name()
		if strings.HasSuffix(name, ".tmp") || strings.Contains(name, ".tmp.") {
			t.Fatalf("temporary file left behind: %s", name)
		}
	}
	assertMode(t, filepath.Join(dir, "device.key"), 0o600)
	assertMode(t, filepath.Join(dir, "state.json"), 0o600)
}

func enrollmentResponse(nodeID string) State {
	return State{
		NodeID:          nodeID,
		RunnerTag:       "repair-node-" + nodeID,
		AllowedProjects: []string{"demo"},
	}
}

func newEnrollmentServer(t *testing.T, status int, base State, mismatchKey bool) *httptest.Server {
	t.Helper()
	caPub, caPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	caCert, err := selfSignCA(caPub, caPriv)
	if err != nil {
		t.Fatal(err)
	}
	caPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: caCert})

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/v1/nodes/join" {
			http.NotFound(w, r)
			return
		}
		body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		var req struct {
			Name   string `json:"name"`
			Code   string `json:"code"`
			CSRPEm string `json:"csrPem"`
		}
		if err := json.Unmarshal(body, &req); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		if status != http.StatusCreated {
			w.WriteHeader(status)
			_, _ = w.Write([]byte(`{"title":"failed"}`))
			return
		}

		pub := mustPublicKeyFromCSR(t, req.CSRPEm)
		if mismatchKey {
			wrongPub, _, err := ed25519.GenerateKey(rand.Reader)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
			pub = wrongPub
		}
		certDER, err := signDeviceCert(caPriv, caCert, pub, base.NodeID)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		resp := base
		resp.CertificatePEM = string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certDER}))
		resp.CAPEM = string(caPEM)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(resp)
	}))
	t.Cleanup(server.Close)
	return server
}

func mustPublicKeyFromCSR(t *testing.T, csrPEM string) ed25519.PublicKey {
	t.Helper()
	block, _ := pem.Decode([]byte(csrPEM))
	if block == nil {
		t.Fatal("csr pem decode failed")
	}
	csr, err := x509.ParseCertificateRequest(block.Bytes)
	if err != nil {
		t.Fatal(err)
	}
	pub, ok := csr.PublicKey.(ed25519.PublicKey)
	if !ok {
		t.Fatalf("csr pub type %T", csr.PublicKey)
	}
	return pub
}

func selfSignCA(pub ed25519.PublicKey, priv ed25519.PrivateKey) ([]byte, error) {
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "repair-node-test-ca"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(24 * time.Hour),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	return x509.CreateCertificate(rand.Reader, tmpl, tmpl, pub, priv)
}

func signDeviceCert(caPriv ed25519.PrivateKey, caCertDER []byte, pub ed25519.PublicKey, nodeID string) ([]byte, error) {
	caCert, err := x509.ParseCertificate(caCertDER)
	if err != nil {
		return nil, err
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject:      pkix.Name{CommonName: "repair-node:" + nodeID},
		NotBefore:    time.Now().Add(-time.Minute),
		NotAfter:     time.Now().Add(90 * 24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}
	return x509.CreateCertificate(rand.Reader, tmpl, caCert, pub, caPriv)
}

func mustRead(t *testing.T, path string) []byte {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func assertMode(t *testing.T, path string, want os.FileMode) {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS == "windows" {
		return
	}
	if got := info.Mode().Perm(); got != want {
		t.Fatalf("%s mode = %o, want %o", path, got, want)
	}
}

func assertDeviceKeyProtected(t *testing.T, path string) {
	t.Helper()
	raw := mustRead(t, path)
	if runtime.GOOS == "windows" {
		if bytes.Contains(raw, []byte("PRIVATE KEY")) {
			t.Fatal("windows device.key must be DPAPI-protected, not plaintext PEM")
		}
		return
	}
	block, _ := pem.Decode(raw)
	if block == nil || !strings.Contains(block.Type, "PRIVATE KEY") {
		t.Fatalf("unix device.key must be private key PEM, got type %q", blockType(block))
	}
	if _, err := x509.ParsePKCS8PrivateKey(block.Bytes); err != nil {
		t.Fatal(err)
	}
}

func blockType(block *pem.Block) string {
	if block == nil {
		return ""
	}
	return block.Type
}
