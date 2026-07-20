package com.company.loopengine.execution.job;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class RepairJobService {
    public static final String ARTIFACT_PENDING = "ARTIFACT_PENDING";
    public static final String FAILED = "FAILED";
    public static final String PENDING = "PENDING";
    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";

    private static final Set<String> INFRA_FAILURE_REASONS = Set.of(
        "runner_system_failure",
        "stuck_or_timeout_failure",
        "api_failure",
        "runner_unsupported");
    private static final Set<String> FUNCTIONAL_FAILURE_REASONS = Set.of(
        "script_failure",
        "validation_failure",
        "opencode_failure");

    private final AttemptStore attempts;
    private final PublishQueue publishQueue;
    private final JobDeliveryStore deliveries;
    private final AuditSink audit;
    private final TransactionTemplate transactions;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Autowired
    public RepairJobService(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            JsonMapper jsonMapper) {
        this(
            new JdbcAttemptStore(jdbc, jsonMapper),
            new JdbcPublishQueue(jdbc),
            new JdbcJobDeliveryStore(jdbc, Clock.systemUTC()),
            new JdbcAuditSink(jdbc),
            new TransactionTemplate(transactionManager),
            jsonMapper,
            Clock.systemUTC());
    }

    RepairJobService(
            AttemptStore attempts,
            PublishQueue publishQueue,
            JobDeliveryStore deliveries,
            AuditSink audit) {
        this(attempts, publishQueue, deliveries, audit, null, null, Clock.systemUTC());
    }

    RepairJobService(
            AttemptStore attempts,
            PublishQueue publishQueue,
            JobDeliveryStore deliveries,
            AuditSink audit,
            TransactionTemplate transactions,
            JsonMapper jsonMapper,
            Clock clock) {
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.publishQueue = Objects.requireNonNull(publishQueue, "publishQueue");
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = transactions;
        this.jsonMapper = jsonMapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public void handle(JobHook hook) {
        Objects.requireNonNull(hook, "hook");
        if (transactions == null) {
            handleInTransaction(hook);
            return;
        }
        transactions.executeWithoutResult(status -> handleInTransaction(hook));
    }

    public JobHook parseHook(String eventUuid, String payloadJson) {
        Objects.requireNonNull(eventUuid, "eventUuid");
        Objects.requireNonNull(payloadJson, "payloadJson");
        if (jsonMapper == null) {
            throw new IllegalStateException("jsonMapper required to parse hooks");
        }
        JsonNode root = jsonMapper.readTree(payloadJson);
        long projectId = root.path("project_id").asLong();
        long jobId = root.path("build_id").asLong();
        String status = root.path("build_status").asText("");
        String failureReason = textOrNull(root.path("build_failure_reason"));
        return new JobHook(projectId, jobId, status, eventUuid, failureReason, payloadJson);
    }

    private void handleInTransaction(JobHook hook) {
        if (!deliveries.insertIfAbsent(hook.eventUuid(), hook.jobId(), hook.payloadJson())) {
            return;
        }
        Attempt attempt = attempts.findByJobId(hook.jobId());
        if (attempt == null) {
            audit.unknownJob(hook.jobId(), hook.eventUuid());
            return;
        }
        String status = normalize(hook.status());
        if ("success".equals(status)) {
            handleSuccess(attempt);
            return;
        }
        if (Set.of("failed", "canceled", "cancelled", "timedout").contains(status)) {
            handleTerminalFailure(attempt, hook);
        }
    }

    private void handleSuccess(Attempt attempt) {
        if (!RUNNING.equals(attempt.state()) && !ARTIFACT_PENDING.equals(attempt.state())) {
            return;
        }
        publishQueue.enqueuePending(attempt.taskId(), attempt.id());
        attempts.update(attempt.withState(ARTIFACT_PENDING));
    }

    private void handleTerminalFailure(Attempt attempt, JobHook hook) {
        if (!RUNNING.equals(attempt.state())) {
            return;
        }
        Attempt finished = attempt.withState(FAILED);
        attempts.update(finished);
        attempts.releaseNodeSlot(attempt.nodeId());

        boolean canRetry = attempt.attemptNo() < attempt.profile().maxExternalAttempts();
        if (canRetry && shouldRequeue(hook.failureReason(), attempt.profile())) {
            attempts.requeueTask(attempt.taskId(), attempt.nodeId());
            return;
        }
        attempts.finishTaskAndDefectFailed(attempt.taskId(), hook.eventUuid(), hook.failureReason());
    }

    /**
     * Only explicit Runner/system/API/stuck reasons requeue as infrastructure failures.
     * Script/validation/OpenCode requeue only when {@code retryFunctionalFailure=true}.
     * Null/blank reasons (typical for canceled/timedout) never auto-requeue.
     */
    static boolean shouldRequeue(String failureReason, Profile profile) {
        if (isInfrastructureFailure(failureReason)) {
            return true;
        }
        return isFunctionalFailure(failureReason) && profile.retryFunctionalFailure();
    }

    private static boolean isInfrastructureFailure(String failureReason) {
        if (failureReason == null || failureReason.isBlank()) {
            return false;
        }
        return INFRA_FAILURE_REASONS.contains(normalize(failureReason));
    }

    private static boolean isFunctionalFailure(String failureReason) {
        if (failureReason == null || failureReason.isBlank()) {
            return false;
        }
        return FUNCTIONAL_FAILURE_REASONS.contains(normalize(failureReason));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    public record Attempt(
            UUID id,
            UUID taskId,
            UUID nodeId,
            UUID reservationId,
            long jobId,
            int attemptNo,
            String state,
            Profile profile) {
        public Attempt withState(String newState) {
            return new Attempt(id, taskId, nodeId, reservationId, jobId, attemptNo, newState, profile);
        }
    }

    public record Profile(int maxExternalAttempts, boolean retryFunctionalFailure) {}

    public record JobHook(
            long projectId,
            long jobId,
            String status,
            String eventUuid,
            String failureReason,
            String payloadJson) {}

    public interface AttemptStore {
        Attempt findByJobId(long jobId);

        void update(Attempt attempt);

        void finishTaskAndDefectFailed(UUID taskId, String eventUuid, String failureReason);

        void requeueTask(UUID taskId, UUID excludeNodeId);

        void releaseNodeSlot(UUID nodeId);
    }

    public interface PublishQueue {
        int countFor(UUID attemptId);

        void enqueuePending(UUID taskId, UUID attemptId);
    }

    public interface JobDeliveryStore {
        boolean insertIfAbsent(String eventUuid, long jobId, String payloadJson);
    }

    public interface AuditSink {
        void unknownJob(long jobId, String eventUuid);
    }

    static final class JdbcAttemptStore implements AttemptStore {
        private final JdbcClient jdbc;
        private final JsonMapper jsonMapper;

        JdbcAttemptStore(JdbcClient jdbc, JsonMapper jsonMapper) {
            this.jdbc = jdbc;
            this.jsonMapper = jsonMapper;
        }

        @Override
        public Attempt findByJobId(long jobId) {
            Optional<Attempt> found = jdbc.sql("""
                select a.id, a.task_id, a.node_id, a.reservation_id, a.job_id, a.attempt_no, a.state,
                       t.profile_snapshot_json::text as profile_json
                from repair_attempt a
                join repair_task t on t.id = a.task_id
                where a.job_id = :jobId
                """)
                .param("jobId", jobId)
                .query((rs, rowNum) -> new Attempt(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("task_id"),
                    (UUID) rs.getObject("node_id"),
                    (UUID) rs.getObject("reservation_id"),
                    rs.getLong("job_id"),
                    rs.getInt("attempt_no"),
                    rs.getString("state"),
                    parseProfile(rs.getString("profile_json"))))
                .optional();
            return found.orElse(null);
        }

        @Override
        public void update(Attempt attempt) {
            Instant now = Instant.now();
            if (FAILED.equals(attempt.state()) || ARTIFACT_PENDING.equals(attempt.state())) {
                jdbc.sql("""
                    update repair_attempt
                    set state = :state,
                        finished_at = case when :state = 'FAILED' then :finishedAt else finished_at end
                    where id = :id
                    """)
                    .param("state", attempt.state())
                    .param("finishedAt", Timestamp.from(now))
                    .param("id", attempt.id())
                    .update();
                return;
            }
            jdbc.sql("update repair_attempt set state = :state where id = :id")
                .param("state", attempt.state())
                .param("id", attempt.id())
                .update();
        }

        @Override
        public void finishTaskAndDefectFailed(UUID taskId, String eventUuid, String failureReason) {
            Instant now = Instant.now();
            var defect = jdbc.sql("select id, state from defect where id = (select defect_id from repair_task where id = :taskId)")
                .param("taskId", taskId)
                .query((rs, rowNum) -> Map.entry(
                    (UUID) rs.getObject("id"),
                    rs.getString("state")))
                .single();
            UUID defectId = defect.getKey();
            String fromState = defect.getValue();
            jdbc.sql("update repair_task set state = 'FAILED', updated_at = :now where id = :id")
                .param("now", Timestamp.from(now))
                .param("id", taskId)
                .update();
            jdbc.sql("update defect set state = 'FAILED', updated_at = :now where id = :id")
                .param("now", Timestamp.from(now))
                .param("id", defectId)
                .update();
            jdbc.sql("""
                insert into defect_transition(defect_id, from_state, to_state, reason, source_event_uuid)
                values (:defectId, :fromState, 'FAILED', :reason, :eventUuid)
                """)
                .param("defectId", defectId)
                .param("fromState", fromState)
                .param("reason", failureReason == null || failureReason.isBlank()
                    ? "JOB_TERMINAL_FAILED"
                    : failureReason)
                .param("eventUuid", eventUuid)
                .update();
            jdbc.sql("""
                insert into outbox_event(id, aggregate_type, aggregate_id, event_type, payload_json, occurred_at)
                values (:id, 'DEFECT', :aggregateId, 'GITLAB_ISSUE_FAILED', cast(:payload as jsonb), :occurredAt)
                on conflict (id) do nothing
                """)
                .param("id", UUID.randomUUID())
                .param("aggregateId", defectId.toString())
                .param(
                    "payload",
                    "{\"defectId\":\"" + defectId + "\",\"taskId\":\"" + taskId
                        + "\",\"eventUuid\":\"" + eventUuid + "\"}")
                .param("occurredAt", Timestamp.from(now))
                .update();
        }

        @Override
        public void requeueTask(UUID taskId, UUID excludeNodeId) {
            Instant now = Instant.now();
            // Prefer any other eligible node on the next schedule: drop sticky request for the failed node.
            jdbc.sql("""
                update repair_task
                set state = 'QUEUED',
                    requested_node_id = case
                      when requested_node_id = :excludeNodeId then null
                      else requested_node_id
                    end,
                    updated_at = :now
                where id = :id
                """)
                .param("excludeNodeId", excludeNodeId)
                .param("now", Timestamp.from(now))
                .param("id", taskId)
                .update();
        }

        @Override
        public void releaseNodeSlot(UUID nodeId) {
            jdbc.sql("""
                update repair_node
                set active_slots = greatest(active_slots - 1, 0),
                    updated_at = :now
                where id = :id
                """)
                .param("now", Timestamp.from(Instant.now()))
                .param("id", nodeId)
                .update();
        }

        private Profile parseProfile(String profileJson) {
            JsonNode root = jsonMapper.readTree(profileJson);
            int maxExternalAttempts = root.path("maxExternalAttempts").asInt(2);
            boolean retryFunctionalFailure = root.path("retryFunctionalFailure").asBoolean(false);
            return new Profile(maxExternalAttempts, retryFunctionalFailure);
        }
    }

    static final class JdbcPublishQueue implements PublishQueue {
        private final JdbcClient jdbc;

        JdbcPublishQueue(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public int countFor(UUID attemptId) {
            return jdbc.sql("select count(*) from publish_record where attempt_id = :attemptId")
                .param("attemptId", attemptId)
                .query(Integer.class)
                .single();
        }

        @Override
        public void enqueuePending(UUID taskId, UUID attemptId) {
            Integer existing = jdbc.sql("select count(*) from publish_record where attempt_id = :attemptId")
                .param("attemptId", attemptId)
                .query(Integer.class)
                .single();
            if (existing != null && existing > 0) {
                return;
            }
            jdbc.sql("""
                insert into publish_record(id, task_id, attempt_id, state)
                values (:id, :taskId, :attemptId, :state)
                on conflict (attempt_id) do nothing
                """)
                .param("id", UUID.randomUUID())
                .param("taskId", taskId)
                .param("attemptId", attemptId)
                .param("state", PENDING)
                .update();
        }
    }

    static final class JdbcJobDeliveryStore implements JobDeliveryStore {
        private final JdbcClient jdbc;
        private final Clock clock;

        JdbcJobDeliveryStore(JdbcClient jdbc, Clock clock) {
            this.jdbc = jdbc;
            this.clock = clock;
        }

        @Override
        public boolean insertIfAbsent(String eventUuid, long jobId, String payloadJson) {
            return jdbc.sql("""
                insert into job_delivery(event_uuid, job_id, payload_json, received_at)
                values (:uuid, :jobId, cast(:payload as jsonb), :receivedAt)
                on conflict (event_uuid) do nothing
                """)
                .param("uuid", eventUuid)
                .param("jobId", jobId)
                .param("payload", payloadJson)
                .param("receivedAt", Timestamp.from(clock.instant()))
                .update() == 1;
        }
    }

    static final class JdbcAuditSink implements AuditSink {
        private final JdbcClient jdbc;

        JdbcAuditSink(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void unknownJob(long jobId, String eventUuid) {
            jdbc.sql("""
                insert into audit_log(
                  actor_type, actor_id, action, object_type, object_id, request_id, detail_json)
                values (
                  'SYSTEM', 'repair-job-hook', 'JOB_HOOK_UNKNOWN_JOB', 'repair_job', :objectId,
                  :requestId, cast(:detail as jsonb))
                """)
                .param("objectId", Long.toString(jobId))
                .param("requestId", eventUuid)
                .param("detail", "{\"jobId\":" + jobId + ",\"eventUuid\":\"" + eventUuid + "\"}")
                .update();
        }
    }
}
