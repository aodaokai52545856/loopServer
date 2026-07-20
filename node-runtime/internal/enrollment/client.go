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
	"fmt"
	"io"
	"net/http"
	"strings"
)

type JoinRequest struct {
	Name string
	Code string
}

type State struct {
	NodeID          string   `json:"nodeId"`
	Server          string   `json:"server"`
	CertificatePEM  string   `json:"certificatePem"`
	CAPEM           string   `json:"caPem"`
	RunnerTag       string   `json:"runnerTag"`
	AllowedProjects []string `json:"allowedProjects"`
}

type KeyStore interface {
	Save(privateKey ed25519.PrivateKey, state State) error
}

type Client struct {
	server string
	store  KeyStore
	http   *http.Client
}

func NewClient(server string, store KeyStore, httpClient *http.Client) *Client {
	if httpClient == nil {
		httpClient = http.DefaultClient
	}
	return &Client{
		server: strings.TrimRight(server, "/"),
		store:  store,
		http:   httpClient,
	}
}

func (c *Client) Join(ctx context.Context, request JoinRequest) (State, error) {
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return State{}, err
	}
	csr, err := createCSR(privateKey, request.Name)
	if err != nil {
		return State{}, err
	}
	body, err := json.Marshal(map[string]any{
		"name":   request.Name,
		"code":   request.Code,
		"csrPem": csr,
	})
	if err != nil {
		return State{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.server+"/api/v1/nodes/join", bytes.NewReader(body))
	if err != nil {
		return State{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return State{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		return State{}, decodeAPIError(resp)
	}
	var state State
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&state); err != nil {
		return State{}, err
	}
	state.Server = c.server
	if err := verifyCertificate(state, publicKey); err != nil {
		return State{}, err
	}
	if err := c.store.Save(privateKey, state); err != nil {
		return State{}, err
	}
	return state, nil
}

func createCSR(privateKey ed25519.PrivateKey, name string) (string, error) {
	der, err := x509.CreateCertificateRequest(rand.Reader, &x509.CertificateRequest{
		Subject: pkix.Name{CommonName: name},
	}, privateKey)
	if err != nil {
		return "", err
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE REQUEST", Bytes: der})), nil
}

func verifyCertificate(state State, publicKey ed25519.PublicKey) error {
	block, _ := pem.Decode([]byte(state.CertificatePEM))
	if block == nil {
		return fmt.Errorf("device certificate PEM is missing")
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return fmt.Errorf("parse device certificate: %w", err)
	}
	pub, ok := cert.PublicKey.(ed25519.PublicKey)
	if !ok {
		return fmt.Errorf("device certificate public key type %T", cert.PublicKey)
	}
	if !bytes.Equal(pub, publicKey) {
		return fmt.Errorf("device certificate public key mismatch")
	}
	return nil
}

func decodeAPIError(resp *http.Response) error {
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if len(body) == 0 {
		return fmt.Errorf("enrollment failed: HTTP %d", resp.StatusCode)
	}
	return fmt.Errorf("enrollment failed: HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(body)))
}
