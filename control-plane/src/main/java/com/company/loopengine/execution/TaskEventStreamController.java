package com.company.loopengine.execution;

import com.company.loopengine.query.DashboardQueryController.Viewer;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/tasks")
public class TaskEventStreamController {
    private static final Duration MAX_DURATION = Duration.ofMinutes(30);
    private static final Duration KEEPALIVE = Duration.ofSeconds(15);
    private static final int MAX_STREAMS_PER_USER = 5;

    private final JdbcClient jdbc;
    private final boolean completeAfterHistory;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "task-event-sse");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, AtomicInteger> streamsByUser = new ConcurrentHashMap<>();

    public TaskEventStreamController(
            JdbcClient jdbc,
            @Value("${loop.query.sse-test-complete-after-history:false}") boolean completeAfterHistory) {
        this.jdbc = jdbc;
        this.completeAfterHistory = completeAfterHistory;
    }

    @GetMapping(path = "/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(
            Authentication authentication,
            @PathVariable UUID taskId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        Viewer viewer = Viewer.from(authentication);
        if (!viewer.canSeeTask(jdbc, taskId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        String userKey = viewer.name();
        AtomicInteger counter = streamsByUser.computeIfAbsent(userKey, ignored -> new AtomicInteger());
        if (counter.incrementAndGet() > MAX_STREAMS_PER_USER) {
            counter.decrementAndGet();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many open event streams");
        }

        SseEmitter emitter = new SseEmitter(MAX_DURATION.toMillis());
        Runnable release = () -> {
            AtomicInteger current = streamsByUser.get(userKey);
            if (current != null) {
                current.decrementAndGet();
            }
        };
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        emitter.onError(ex -> release.run());

        LastEventCursor cursor = LastEventCursor.parse(lastEventId);
        executor.execute(() -> runStream(emitter, taskId, cursor));
        return emitter;
    }

    private void runStream(SseEmitter emitter, UUID taskId, LastEventCursor cursor) {
        Instant deadline = Instant.now().plus(MAX_DURATION);
        Instant nextKeepalive = Instant.now().plus(KEEPALIVE);
        try {
            emitter.send(SseEmitter.event().reconnectTime(3000));
            List<EventRow> history = loadEventsAfter(taskId, cursor);
            for (EventRow event : history) {
                sendEvent(emitter, event);
                cursor = new LastEventCursor(event.attemptId(), event.seq());
            }
            if (completeAfterHistory) {
                emitter.complete();
                return;
            }
            while (Instant.now().isBefore(deadline)) {
                List<EventRow> newer = loadEventsAfter(taskId, cursor);
                for (EventRow event : newer) {
                    sendEvent(emitter, event);
                    cursor = new LastEventCursor(event.attemptId(), event.seq());
                }
                Instant now = Instant.now();
                if (!now.isBefore(nextKeepalive)) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    nextKeepalive = now.plus(KEEPALIVE);
                }
                Thread.sleep(1000L);
            }
            emitter.complete();
        } catch (Exception ex) {
            try {
                emitter.completeWithError(ex);
            } catch (Exception ignored) {
                // already completed
            }
        }
    }

    private void sendEvent(SseEmitter emitter, EventRow event) throws IOException {
        String id = event.attemptId() + ":" + event.seq();
        emitter.send(SseEmitter.event()
                .id(id)
                .name(event.type())
                .data(Map.of(
                        "attemptId", event.attemptId().toString(),
                        "seq", event.seq(),
                        "type", event.type(),
                        "eventTime", event.eventTime().toString(),
                        "payloadJson", event.payloadJson())));
    }

    private List<EventRow> loadEventsAfter(UUID taskId, LastEventCursor cursor) {
        if (cursor == null) {
            return jdbc.sql("""
                    select e.attempt_id, e.seq, e.event_time, e.type, e.payload_json::text as payload_json
                    from task_event e
                    join repair_attempt a on a.id = e.attempt_id
                    where a.task_id = :taskId
                    order by e.attempt_id, e.seq
                    """)
                    .param("taskId", taskId)
                    .query((rs, rowNum) -> new EventRow(
                            rs.getObject("attempt_id", UUID.class),
                            rs.getLong("seq"),
                            rs.getTimestamp("event_time").toInstant(),
                            rs.getString("type"),
                            rs.getString("payload_json")))
                    .list();
        }
        return jdbc.sql("""
                select e.attempt_id, e.seq, e.event_time, e.type, e.payload_json::text as payload_json
                from task_event e
                join repair_attempt a on a.id = e.attempt_id
                where a.task_id = :taskId
                  and e.attempt_id = :attemptId
                  and e.seq > :seq
                order by e.seq
                """)
                .param("taskId", taskId)
                .param("attemptId", cursor.attemptId())
                .param("seq", cursor.seq())
                .query((rs, rowNum) -> new EventRow(
                        rs.getObject("attempt_id", UUID.class),
                        rs.getLong("seq"),
                        rs.getTimestamp("event_time").toInstant(),
                        rs.getString("type"),
                        rs.getString("payload_json")))
                .list();
    }

    private record EventRow(UUID attemptId, long seq, Instant eventTime, String type, String payloadJson) {}

    private record LastEventCursor(UUID attemptId, long seq) {
        static LastEventCursor parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            int idx = raw.lastIndexOf(':');
            if (idx <= 0 || idx == raw.length() - 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Last-Event-ID");
            }
            try {
                return new LastEventCursor(
                        UUID.fromString(raw.substring(0, idx)), Long.parseLong(raw.substring(idx + 1)));
            } catch (RuntimeException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Last-Event-ID");
            }
        }
    }
}
