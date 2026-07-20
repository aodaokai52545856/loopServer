package heartbeat

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Config is the desired remote configuration returned by the control plane.
type Config struct {
	Concurrency     int      `json:"concurrency"`
	AllowedProjects []string `json:"allowedProjects"`
	Drain           bool     `json:"drain"`
}

// Request is the node heartbeat payload.
type Request struct {
	ObservedAt       time.Time `json:"observedAt"`
	AppliedRevision  int64     `json:"appliedRevision"`
	Runner           Runner    `json:"runner"`
	Slots            Slots     `json:"slots"`
	Resources        Resources `json:"resources"`
	Tools            Tools     `json:"tools"`
	ActiveAttemptIds []string  `json:"activeAttemptIds"`
	LastError        *string   `json:"lastError"`
}

type Runner struct {
	Status  string `json:"status"`
	Version string `json:"version"`
}

type Slots struct {
	Active int `json:"active"`
	Limit  int `json:"limit"`
}

type Resources struct {
	CPUPercent           float64 `json:"cpuPercent"`
	MemoryAvailableBytes int64   `json:"memoryAvailableBytes"`
	DiskAvailableBytes   int64   `json:"diskAvailableBytes"`
}

type Tools struct {
	OS       string `json:"os"`
	Arch     string `json:"arch"`
	Java     string `json:"java"`
	Maven    string `json:"maven"`
	Node     string `json:"node"`
	Pnpm     string `json:"pnpm"`
	OpenCode string `json:"opencode"`
}

// Response is the desired-state reply from the control plane.
type Response struct {
	DesiredRevision int64   `json:"desiredRevision"`
	Config          *Config `json:"config"`
}

// Client posts heartbeats over an mTLS HTTP client.
type Client struct {
	server string
	nodeID string
	http   *http.Client
}

// NewClient builds a heartbeat client. server is the control-plane base URL.
func NewClient(server, nodeID string, httpClient *http.Client) *Client {
	if httpClient == nil {
		httpClient = http.DefaultClient
	}
	return &Client{
		server: strings.TrimRight(server, "/"),
		nodeID: nodeID,
		http:   httpClient,
	}
}

// NewMTLSClient builds an HTTP client that presents the device certificate.
func NewMTLSClient(certPEM, keyPEM, caPEM []byte) (*http.Client, error) {
	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return nil, fmt.Errorf("load device key pair: %w", err)
	}
	pool := x509.NewCertPool()
	if caPEM != nil && !pool.AppendCertsFromPEM(caPEM) {
		return nil, fmt.Errorf("parse device CA PEM")
	}
	tlsConfig := &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
	}
	if caPEM != nil {
		tlsConfig.RootCAs = pool
	}
	return &http.Client{
		Timeout: 15 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: tlsConfig,
		},
	}, nil
}

// Heartbeat sends one heartbeat request and decodes the desired-state response.
func (c *Client) Heartbeat(ctx context.Context, req Request) (Response, error) {
	body, err := json.Marshal(req)
	if err != nil {
		return Response{}, err
	}
	url := c.server + "/api/node/v1/nodes/" + c.nodeID + "/heartbeat"
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return Response{}, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(httpReq)
	if err != nil {
		return Response{}, err
	}
	defer resp.Body.Close()
	limited := io.LimitReader(resp.Body, 1<<20)
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		payload, _ := io.ReadAll(limited)
		return Response{}, &HTTPError{StatusCode: resp.StatusCode, Body: strings.TrimSpace(string(payload))}
	}
	var out Response
	if err := json.NewDecoder(limited).Decode(&out); err != nil {
		return Response{}, err
	}
	return out, nil
}

// HTTPError is a non-success heartbeat HTTP status.
type HTTPError struct {
	StatusCode int
	Body       string
}

func (e *HTTPError) Error() string {
	if e.Body == "" {
		return fmt.Sprintf("heartbeat HTTP %d", e.StatusCode)
	}
	return fmt.Sprintf("heartbeat HTTP %d: %s", e.StatusCode, e.Body)
}
