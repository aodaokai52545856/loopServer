package spool

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	eventsFileName = "events.jsonl"
	ackFileName    = "events.ack"
	maxBatchEvents = 100
	maxBatchBytes  = 256 * 1024
	syncInterval   = time.Second
)

// Event is one structured attempt event persisted as a JSONL line.
type Event struct {
	TaskID    string         `json:"taskId,omitempty"`
	AttemptID string         `json:"attemptId,omitempty"`
	NodeID    string         `json:"nodeId,omitempty"`
	Seq       int64          `json:"seq"`
	Time      time.Time      `json:"time"`
	Type      string         `json:"type"`
	Payload   map[string]any `json:"payload,omitempty"`
}

// Spool appends events to out/events.jsonl before any network send.
type Spool struct {
	mu       sync.Mutex
	dir      string
	file     *os.File
	events   []Event
	acked    int64
	nextSeq  int64
	lastSync time.Time
}

// Open creates or reopens a spool under outDir (events.jsonl + events.ack).
func Open(outDir string) (*Spool, error) {
	if err := os.MkdirAll(outDir, 0o755); err != nil {
		return nil, err
	}
	path := filepath.Join(outDir, eventsFileName)
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE, 0o644)
	if err != nil {
		return nil, err
	}
	s := &Spool{
		dir:      outDir,
		file:     f,
		nextSeq:  1,
		lastSync: time.Now(),
	}
	if err := s.loadEvents(); err != nil {
		_ = f.Close()
		return nil, err
	}
	acked, err := readAck(filepath.Join(outDir, ackFileName))
	if err != nil {
		_ = f.Close()
		return nil, err
	}
	s.acked = acked
	return s, nil
}

// Close closes the underlying JSONL file.
func (s *Spool) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.file == nil {
		return nil
	}
	err := s.file.Close()
	s.file = nil
	return err
}

// Acked returns the greatest locally persisted cumulative acknowledgment.
func (s *Spool) Acked() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.acked
}

// Append assigns the next monotonic seq and appends one compact JSON line.
func (s *Spool) Append(ev Event) (Event, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.file == nil {
		return Event{}, errors.New("spool closed")
	}
	if ev.Time.IsZero() {
		ev.Time = time.Now().UTC()
	}
	ev.Seq = s.nextSeq
	s.nextSeq++
	line, err := json.Marshal(ev)
	if err != nil {
		return Event{}, err
	}
	if _, err := s.file.Write(append(line, '\n')); err != nil {
		return Event{}, err
	}
	s.events = append(s.events, ev)
	if shouldSync(ev.Type) || time.Since(s.lastSync) >= syncInterval {
		if err := s.file.Sync(); err != nil {
			return Event{}, err
		}
		s.lastSync = time.Now()
	}
	return ev, nil
}

// Pending returns complete events with seq greater than the local ack.
func (s *Spool) Pending() []Event {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Event, 0)
	for _, ev := range s.events {
		if ev.Seq > s.acked {
			out = append(out, ev)
		}
	}
	return out
}

// PersistAck atomically writes events.ack after the server returns ackSeq.
func (s *Spool) PersistAck(ackSeq int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if ackSeq < s.acked {
		return nil
	}
	path := filepath.Join(s.dir, ackFileName)
	tmp := path + ".tmp"
	content := []byte(fmt.Sprintf("%d\n", ackSeq))
	if err := os.WriteFile(tmp, content, 0o644); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		return err
	}
	s.acked = ackSeq
	return nil
}

func (s *Spool) loadEvents() error {
	if _, err := s.file.Seek(0, 0); err != nil {
		return err
	}
	data, err := readAll(s.file)
	if err != nil {
		return err
	}
	var events []Event
	var maxSeq int64
	var offset int
	for offset < len(data) {
		nl := bytes.IndexByte(data[offset:], '\n')
		if nl < 0 {
			// Truncated final line without newline: drop it.
			break
		}
		end := offset + nl
		line := bytes.TrimSpace(data[offset:end])
		next := end + 1
		if len(line) == 0 {
			offset = next
			continue
		}
		var ev Event
		if err := json.Unmarshal(line, &ev); err != nil {
			// Corrupt final complete-looking line: keep prior events.
			break
		}
		if ev.Seq <= 0 {
			return fmt.Errorf("invalid event seq %d", ev.Seq)
		}
		events = append(events, ev)
		if ev.Seq > maxSeq {
			maxSeq = ev.Seq
		}
		offset = next
	}
	if _, err := s.file.Seek(int64(offset), 0); err != nil {
		return err
	}
	if err := s.file.Truncate(int64(offset)); err != nil {
		return err
	}
	s.events = events
	if maxSeq > 0 {
		s.nextSeq = maxSeq + 1
	}
	return nil
}

func readAll(f *os.File) ([]byte, error) {
	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	buf := make([]byte, info.Size())
	n, err := f.Read(buf)
	if err != nil && n == 0 {
		return nil, err
	}
	return buf[:n], nil
}

func readAck(path string) (int64, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return 0, nil
	}
	if err != nil {
		return 0, err
	}
	data = bytes.TrimSpace(data)
	if len(data) == 0 {
		return 0, nil
	}
	var ack int64
	if _, err := fmt.Sscan(string(data), &ack); err != nil {
		return 0, fmt.Errorf("parse events.ack: %w", err)
	}
	return ack, nil
}

func shouldSync(eventType string) bool {
	return strings.HasPrefix(eventType, "test.") || strings.HasSuffix(eventType, ".finished")
}
