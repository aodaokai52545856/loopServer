package spool

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand"
	"time"
)

// ErrConflict indicates the server rejected the batch with HTTP 409.
var ErrConflict = errors.New("event sequence conflict")

// EventAPI posts ordered event batches and queries cumulative acknowledgments.
type EventAPI interface {
	PostBatch(ctx context.Context, events []Event) (ackSeq int64, err error)
	QueryAck(ctx context.Context) (ackSeq int64, err error)
}

// Uploader replays local spool events after cumulative server acknowledgments.
type Uploader struct {
	spool     *Spool
	api       EventAPI
	batchSize int
	sleep     func(context.Context, time.Duration) error
	now       func() time.Time
	randFloat func() float64
}

// NewUploader builds an uploader. batchSize is capped at 100; zero uses 100.
func NewUploader(spool *Spool, api EventAPI, batchSize int) *Uploader {
	if batchSize <= 0 || batchSize > maxBatchEvents {
		batchSize = maxBatchEvents
	}
	return &Uploader{
		spool:     spool,
		api:       api,
		batchSize: batchSize,
		sleep: func(ctx context.Context, d time.Duration) error {
			timer := time.NewTimer(d)
			defer timer.Stop()
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-timer.C:
				return nil
			}
		},
		now:       time.Now,
		randFloat: rand.Float64,
	}
}

// Flush posts pending events in order until the spool is fully acknowledged.
func (u *Uploader) Flush(ctx context.Context) error {
	for {
		pending := u.spool.Pending()
		if len(pending) == 0 {
			return nil
		}
		batch := takeBatch(pending, u.batchSize)
		ackSeq, err := u.postWithRetry(ctx, batch)
		if err != nil {
			return err
		}
		if err := u.spool.PersistAck(ackSeq); err != nil {
			return err
		}
		if ackSeq < batch[0].Seq {
			// Server did not advance; avoid a tight loop on a stuck peer.
			if err := u.sleep(ctx, time.Second); err != nil {
				return err
			}
		}
	}
}

func (u *Uploader) postWithRetry(ctx context.Context, batch []Event) (int64, error) {
	var prevSleep time.Duration
	for attempt := 0; ; attempt++ {
		ackSeq, err := u.api.PostBatch(ctx, batch)
		if err == nil {
			return ackSeq, nil
		}
		if errors.Is(err, ErrConflict) {
			serverAck, qerr := u.api.QueryAck(ctx)
			if qerr != nil {
				return 0, qerr
			}
			if err := u.spool.PersistAck(serverAck); err != nil {
				return 0, err
			}
			return serverAck, nil
		}
		if !retryable(err) {
			return 0, err
		}
		sleepFor := decorrelatedJitter(prevSleep, u.randFloat)
		prevSleep = sleepFor
		if err := u.sleep(ctx, sleepFor); err != nil {
			return 0, err
		}
	}
}

func takeBatch(events []Event, batchSize int) []Event {
	if len(events) == 0 {
		return nil
	}
	n := 0
	var size int
	for n < len(events) && n < batchSize {
		line, err := json.Marshal(events[n])
		if err != nil {
			break
		}
		next := size + len(line)
		if n > 0 && next > maxBatchBytes {
			break
		}
		if n == 0 && len(line) > maxBatchBytes {
			// Single oversized event: still attempt upload of that one item.
			return events[:1]
		}
		size = next
		n++
	}
	return events[:n]
}

func retryable(err error) bool {
	var re *RetryableError
	return errors.As(err, &re)
}

// RetryableError wraps transient network / 429 / 5xx failures.
type RetryableError struct {
	Err error
}

func (e *RetryableError) Error() string {
	if e.Err == nil {
		return "retryable upload error"
	}
	return e.Err.Error()
}

func (e *RetryableError) Unwrap() error { return e.Err }

func decorrelatedJitter(prev time.Duration, randFloat func() float64) time.Duration {
	const capSleep = 30 * time.Second
	base := 100 * time.Millisecond
	if prev <= 0 {
		prev = base
	}
	next := time.Duration(float64(base) + randFloat()*float64(prev*3))
	if next > capSleep {
		return capSleep
	}
	if next < base {
		return base
	}
	return next
}

// FormatBatchError helps HTTP adapters map status codes.
func FormatBatchError(status int, body string) error {
	switch {
	case status == 409:
		return ErrConflict
	case status == 429 || status >= 500:
		return &RetryableError{Err: fmt.Errorf("upload status %d: %s", status, body)}
	default:
		return fmt.Errorf("upload status %d: %s", status, body)
	}
}
