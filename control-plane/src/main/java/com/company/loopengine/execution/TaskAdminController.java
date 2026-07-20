package com.company.loopengine.execution;

import com.company.loopengine.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class TaskAdminController {
    private static final int REASON_MIN = 10;
    private static final int REASON_MAX = 500;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Autowired
    public TaskAdminController(
            JdbcClient jdbc, PlatformTransactionManager transactionManager, JsonMapper jsonMapper) {
        this(jdbc, transactionManager, jsonMapper, Clock.systemUTC());
    }

    TaskAdminController(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            JsonMapper jsonMapper,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(transactionManager);
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping("/api/tasks/{taskId}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','PROJECT_OWNER')")
    ResponseEntity<Map<String, Object>> retry(
            Authentication authentication,
            @PathVariable String taskId,
            @RequestBody JsonNode body,
            HttpServletRequest request) {
        String reason = requireReason(body);
        String requestId = requestId(request);
        Map<String, Object> result = transactions.execute(status -> {
            TaskRow task = requireTask(taskId);
            requireProjectAccess(authentication, task.projectKey());
            if (!"FAILED".equals(task.state()) && !"BLOCKED".equals(task.state())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "only FAILED or BLOCKED tasks can be retried");
            }
            Instant now = clock.instant();
            jdbc.sql("""
                update repair_task
                set state = 'QUEUED', updated_at = :updatedAt
                where id = :id
                """)
                    .param("updatedAt", Timestamp.from(now))
                    .param("id", task.id())
                    .update();
            long auditId = writeAudit(
                    authentication,
                    "TASK_RETRY",
                    "REPAIR_TASK",
                    task.id().toString(),
                    requestId,
                    Map.of("reason", reason, "fromState", task.state(), "toState", "QUEUED"));
            Map<String, Object> bodyOut = new LinkedHashMap<>();
            bodyOut.put("taskId", task.id().toString());
            bodyOut.put("state", "QUEUED");
            bodyOut.put("auditId", auditId);
            return bodyOut;
        });
        return ResponseEntity.accepted().body(result);
    }

    @PostMapping("/api/tasks/{taskId}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','PROJECT_OWNER')")
    ResponseEntity<Map<String, Object>> cancel(
            Authentication authentication,
            @PathVariable String taskId,
            @RequestBody JsonNode body,
            HttpServletRequest request) {
        String reason = requireReason(body);
        String requestId = requestId(request);
        Map<String, Object> result = transactions.execute(status -> {
            TaskRow task = requireTask(taskId);
            requireProjectAccess(authentication, task.projectKey());
            if ("CANCELLED".equals(task.state()) || "READY_FOR_TEST".equals(task.state())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "task cannot be cancelled from state " + task.state());
            }
            Instant now = clock.instant();
            jdbc.sql("""
                update repair_task
                set state = 'CANCELLED', updated_at = :updatedAt
                where id = :id
                """)
                    .param("updatedAt", Timestamp.from(now))
                    .param("id", task.id())
                    .update();
            long auditId = writeAudit(
                    authentication,
                    "TASK_CANCEL",
                    "REPAIR_TASK",
                    task.id().toString(),
                    requestId,
                    Map.of("reason", reason, "fromState", task.state(), "toState", "CANCELLED"));
            Map<String, Object> bodyOut = new LinkedHashMap<>();
            bodyOut.put("taskId", task.id().toString());
            bodyOut.put("state", "CANCELLED");
            bodyOut.put("auditId", auditId);
            return bodyOut;
        });
        return ResponseEntity.accepted().body(result);
    }

    @PostMapping("/api/operations/outbox/{eventId}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<Map<String, Object>> retryOutbox(
            Authentication authentication,
            @PathVariable String eventId,
            @RequestBody JsonNode body,
            HttpServletRequest request) {
        String reason = requireReason(body);
        String requestId = requestId(request);
        UUID id = parseUuid(eventId);
        Map<String, Object> result = transactions.execute(status -> {
            Instant now = clock.instant();
            int updated = jdbc.sql("""
                update outbox_event
                set processed_at = null,
                    available_at = :availableAt,
                    last_error = null
                where id = :id
                """)
                    .param("availableAt", Timestamp.from(now))
                    .param("id", id)
                    .update();
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            long auditId = writeAudit(
                    authentication,
                    "OUTBOX_RETRY",
                    "OUTBOX_EVENT",
                    id.toString(),
                    requestId,
                    Map.of("reason", reason));
            return Map.<String, Object>of("eventId", id.toString(), "auditId", auditId);
        });
        return ResponseEntity.accepted().body(result);
    }

    @PostMapping("/api/operations/webhooks/{eventUuid}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<Map<String, Object>> retryWebhook(
            Authentication authentication,
            @PathVariable String eventUuid,
            @RequestBody JsonNode body,
            HttpServletRequest request) {
        String reason = requireReason(body);
        String requestId = requestId(request);
        Map<String, Object> result = transactions.execute(status -> {
            Instant now = clock.instant();
            int updated = jdbc.sql("""
                update gitlab_webhook_delivery
                set processing_state = 'RETRY',
                    next_attempt_at = :nextAttemptAt,
                    last_error = null
                where event_uuid = :eventUuid
                """)
                    .param("nextAttemptAt", Timestamp.from(now))
                    .param("eventUuid", eventUuid)
                    .update();
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            long auditId = writeAudit(
                    authentication,
                    "WEBHOOK_RETRY",
                    "WEBHOOK_DELIVERY",
                    eventUuid,
                    requestId,
                    Map.of("reason", reason));
            return Map.<String, Object>of("eventUuid", eventUuid, "auditId", auditId);
        });
        return ResponseEntity.accepted().body(result);
    }

    private void requireProjectAccess(Authentication authentication, String projectKey) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (admin) {
            return;
        }
        long gitlabUserId;
        try {
            gitlabUserId = Long.parseLong(authentication.getName());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Boolean owned = jdbc.sql("""
                select exists(
                  select 1
                  from project_profile_owner o
                  join project_profile p on p.id = o.profile_id
                  where p.project_key = :projectKey
                    and o.gitlab_user_id = :gitlabUserId
                )
                """)
                .param("projectKey", projectKey)
                .param("gitlabUserId", gitlabUserId)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(owned)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private TaskRow requireTask(String taskId) {
        return jdbc.sql("""
                select id, project_key, state
                from repair_task
                where cast(id as varchar) = :taskId
                for update
                """)
                .param("taskId", taskId)
                .query((rs, rowNum) -> new TaskRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("project_key"),
                        rs.getString("state")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private long writeAudit(
            Authentication authentication,
            String action,
            String objectType,
            String objectId,
            String requestId,
            Map<String, Object> detail) {
        String detailJson = jsonMapper.writeValueAsString(detail);
        return jdbc.sql("""
                insert into audit_log(
                  actor_type, actor_id, action, object_type, object_id, request_id, detail_json)
                values (
                  'USER', :actorId, :action, :objectType, :objectId, :requestId, cast(:detail as jsonb))
                returning id
                """)
                .param("actorId", authentication.getName())
                .param("action", action)
                .param("objectType", objectType)
                .param("objectId", objectId)
                .param("requestId", requestId)
                .param("detail", detailJson)
                .query(Long.class)
                .single();
    }

    private static String requireReason(JsonNode body) {
        if (body == null || body.isNull()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason must be 10-500 characters");
        }
        String reason = body.path("reason").asString("").trim();
        if (reason.length() < REASON_MIN || reason.length() > REASON_MAX) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "reason must be 10-500 characters");
        }
        return reason;
    }

    private static String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(CorrelationIdFilter.HEADER);
        if (attr instanceof String value && !value.isBlank()) {
            return value;
        }
        return UUID.randomUUID().toString();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid event id");
        }
    }

    private record TaskRow(UUID id, String projectKey, String state) {}
}
