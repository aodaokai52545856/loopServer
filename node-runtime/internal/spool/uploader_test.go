package spool

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

func TestUploaderResumesFromServerAcknowledgedSequence(t *testing.T) {
	spool := newSpoolWithEvents(t, 1, 2, 3, 4, 5)
	api := &fakeEventAPI{acks: []int64{2, 5}}
	uploader := NewUploader(spool, api, 3)
	if err := uploader.Flush(context.Background()); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual([][]int64{{1, 2, 3}, {3, 4, 5}}, api.sentSequences) {
		t.Fatalf("sentSequences = %#v", api.sentSequences)
	}
	if spool.Acked() != 5 {
		t.Fatalf("acked = %d", spool.Acked())
	}
}

func TestSpoolReopensTruncatedTailAndResumesAck(t *testing.T) {
	dir := t.TempDir()
	out := filepath.Join(dir, "out")
	s, err := Open(out)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		if _, err := s.Append(Event{Type: "agent.message", Payload: map[string]any{"i": i}}); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.PersistAck(2); err != nil {
		t.Fatal(err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}

	path := filepath.Join(out, eventsFileName)
	f, err := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.WriteString(`{"seq":4,"type":"agent.message","payl`); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}

	reopened, err := Open(out)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = reopened.Close() })
	if reopened.Acked() != 2 {
		t.Fatalf("acked = %d", reopened.Acked())
	}
	pending := reopened.Pending()
	if len(pending) != 1 || pending[0].Seq != 3 {
		t.Fatalf("pending = %#v", pending)
	}
	if _, err := reopened.Append(Event{Type: "agent.message"}); err != nil {
		t.Fatal(err)
	}
	if got := reopened.Pending(); len(got) != 2 || got[1].Seq != 4 {
		t.Fatalf("after append pending = %#v", got)
	}
}

func TestUploaderDuplicateBatchAfterRestart(t *testing.T) {
	dir := t.TempDir()
	out := filepath.Join(dir, "out")
	s, err := Open(out)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 4; i++ {
		if _, err := s.Append(Event{Type: "agent.message"}); err != nil {
			t.Fatal(err)
		}
	}
	api := &fakeEventAPI{acks: []int64{2}}
	first := s.Pending()[:2]
	ack, err := api.PostBatch(context.Background(), first)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.PersistAck(ack); err != nil {
		t.Fatal(err)
	}
	if s.Acked() != 2 {
		t.Fatalf("acked = %d", s.Acked())
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}

	reopened, err := Open(out)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = reopened.Close() })
	api2 := &fakeEventAPI{acks: []int64{4}}
	if err := NewUploader(reopened, api2, 10).Flush(context.Background()); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual([][]int64{{3, 4}}, api2.sentSequences) {
		t.Fatalf("sentSequences = %#v", api2.sentSequences)
	}
	if reopened.Acked() != 4 {
		t.Fatalf("acked = %d", reopened.Acked())
	}
}

func TestUploaderHandlesConflictByQueryingAck(t *testing.T) {
	s := newSpoolWithEvents(t, 1, 2, 3)
	api := &fakeEventAPI{
		conflictAt: 1,
		queryAck:   2,
	}
	if err := NewUploader(s, api, 10).Flush(context.Background()); err != nil {
		t.Fatal(err)
	}
	if s.Acked() != 3 {
		t.Fatalf("acked = %d", s.Acked())
	}
	if len(api.sentSequences) < 2 {
		t.Fatalf("sentSequences = %#v", api.sentSequences)
	}
	if !reflect.DeepEqual([]int64{3}, api.sentSequences[len(api.sentSequences)-1]) {
		t.Fatalf("final batch = %#v", api.sentSequences[len(api.sentSequences)-1])
	}
}

func newSpoolWithEvents(t *testing.T, seqs ...int64) *Spool {
	t.Helper()
	dir := t.TempDir()
	out := filepath.Join(dir, "out")
	s, err := Open(out)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })
	for range seqs {
		if _, err := s.Append(Event{
			Type:    "agent.message",
			Time:    time.Unix(0, 0).UTC(),
			Payload: map[string]any{},
		}); err != nil {
			t.Fatal(err)
		}
	}
	if len(s.Pending()) != len(seqs) {
		t.Fatalf("pending=%d want=%d", len(s.Pending()), len(seqs))
	}
	for i, ev := range s.Pending() {
		if ev.Seq != seqs[i] {
			t.Fatalf("seq[%d]=%d want %d", i, ev.Seq, seqs[i])
		}
	}
	return s
}

type fakeEventAPI struct {
	acks          []int64
	sentSequences [][]int64
	call          int
	conflictAt    int // 1-based PostBatch call index that returns ErrConflict
	queryAck      int64
	failures      []error
}

func (f *fakeEventAPI) PostBatch(_ context.Context, events []Event) (int64, error) {
	f.call++
	seqs := make([]int64, len(events))
	for i, ev := range events {
		seqs[i] = ev.Seq
	}
	f.sentSequences = append(f.sentSequences, seqs)
	if f.conflictAt > 0 && f.call == f.conflictAt {
		return 0, ErrConflict
	}
	if len(f.failures) > 0 {
		err := f.failures[0]
		f.failures = f.failures[1:]
		return 0, err
	}
	if f.call-1 >= len(f.acks) {
		return seqs[len(seqs)-1], nil
	}
	return f.acks[f.call-1], nil
}

func (f *fakeEventAPI) QueryAck(context.Context) (int64, error) {
	return f.queryAck, nil
}

func TestTakeBatchRespectsByteCap(t *testing.T) {
	big := Event{Seq: 1, Type: "agent.message", Payload: map[string]any{"x": string(make([]byte, 200*1024))}}
	small := Event{Seq: 2, Type: "agent.message", Payload: map[string]any{"x": "y"}}
	batch := takeBatch([]Event{big, small}, 100)
	if len(batch) != 1 || batch[0].Seq != 1 {
		t.Fatalf("batch = %#v", batch)
	}
	line, _ := json.Marshal(big)
	if len(line) <= maxBatchBytes {
		t.Fatalf("fixture too small: %d", len(line))
	}
}
