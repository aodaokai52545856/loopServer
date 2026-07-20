package com.company.loopengine.execution.job;

import static com.company.loopengine.execution.job.RepairJobService.ARTIFACT_PENDING;
import static com.company.loopengine.execution.job.RepairJobService.FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.FORBIDDEN;

import com.company.loopengine.execution.job.RepairJobService.Attempt;
import com.company.loopengine.execution.job.RepairJobService.AttemptStore;
import com.company.loopengine.execution.job.RepairJobService.AuditSink;
import com.company.loopengine.execution.job.RepairJobService.JobDeliveryStore;
import com.company.loopengine.execution.job.RepairJobService.JobHook;
import com.company.loopengine.execution.job.RepairJobService.Profile;
import com.company.loopengine.execution.job.RepairJobService.PublishQueue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class RepairJobServiceTest {
    private static final long CENTRAL_PROJECT_ID = 4242L;
    private static final String JOB_HOOK_SECRET = "job-hook-secret";

    private InMemoryAttempts attempts;
    private InMemoryPublishQueue publishQueue;
    private InMemoryJobDeliveries deliveries;
    private InMemoryAuditSink audit;
    private RepairJobService service;
    private RepairJobWebhookController controller;

    @BeforeEach
    void setUp() {
        attempts = new InMemoryAttempts();
        publishQueue = new InMemoryPublishQueue();
        deliveries = new InMemoryJobDeliveries();
        audit = new InMemoryAuditSink();
        service = new RepairJobService(attempts, publishQueue, deliveries, audit);
        controller = new RepairJobWebhookController(JOB_HOOK_SECRET, CENTRAL_PROJECT_ID, service);
    }

    @Test
    void successfulKnownJobQueuesPublishingOnce() {
        Attempt attempt = runningAttempt(202L);
        service.handle(jobHook(202L, "success", "evt-job-1"));
        service.handle(jobHook(202L, "success", "evt-job-1"));
        assertThat(attempts.get(attempt.id()).state()).isEqualTo(ARTIFACT_PENDING);
        assertThat(publishQueue.countFor(attempt.id())).isEqualTo(1);
    }

    @Test
    void exhaustedFailedJobNeverQueuesPublishing() {
        Attempt attempt = runningAttempt(203L, 2, profileWithMaxExternalAttempts(2));
        service.handle(jobHook(203L, "failed", "evt-job-2"));
        assertThat(attempts.get(attempt.id()).state()).isEqualTo(FAILED);
        assertThat(publishQueue.countFor(attempt.id())).isZero();
        assertThat(attempts.finishedFailedTasks()).contains(attempt.taskId());
        assertThat(attempts.requeuedTasks()).doesNotContain(attempt.taskId());
    }

    @Test
    void canceledWithNullReasonFinishesFailedWithoutRequeue() {
        Attempt attempt = runningAttempt(204L, 1, profileWithMaxExternalAttempts(2));
        service.handle(jobHook(204L, "canceled", "evt-job-cancel", null));
        assertThat(attempts.get(attempt.id()).state()).isEqualTo(FAILED);
        assertThat(publishQueue.countFor(attempt.id())).isZero();
        assertThat(attempts.finishedFailedTasks()).contains(attempt.taskId());
        assertThat(attempts.requeuedTasks()).doesNotContain(attempt.taskId());
    }

    @Test
    void timedoutWithNullReasonFinishesFailedWithoutRequeue() {
        Attempt attempt = runningAttempt(205L, 1, profileWithMaxExternalAttempts(2));
        service.handle(jobHook(205L, "timedout", "evt-job-timeout", null));
        assertThat(attempts.get(attempt.id()).state()).isEqualTo(FAILED);
        assertThat(attempts.finishedFailedTasks()).contains(attempt.taskId());
        assertThat(attempts.requeuedTasks()).doesNotContain(attempt.taskId());
    }

    @Test
    void runnerSystemFailureRequeuesWhenAttemptsRemain() {
        Attempt attempt = runningAttempt(206L, 1, profileWithMaxExternalAttempts(2));
        service.handle(jobHook(206L, "failed", "evt-job-infra", "runner_system_failure"));
        assertThat(attempts.get(attempt.id()).state()).isEqualTo(FAILED);
        assertThat(attempts.requeuedTasks()).contains(attempt.taskId());
        assertThat(attempts.excludedNodesFor(attempt.taskId())).contains(attempt.nodeId());
        assertThat(attempts.finishedFailedTasks()).doesNotContain(attempt.taskId());
        assertThat(publishQueue.countFor(attempt.id())).isZero();
    }

    @Test
    void scriptFailureFinishesFailedUnlessRetryFunctionalFailureEnabled() {
        Attempt noRetry = runningAttempt(207L, 1, new Profile(2, false));
        service.handle(jobHook(207L, "failed", "evt-job-script", "script_failure"));
        assertThat(attempts.finishedFailedTasks()).contains(noRetry.taskId());
        assertThat(attempts.requeuedTasks()).doesNotContain(noRetry.taskId());

        Attempt withRetry = runningAttempt(208L, 1, new Profile(2, true));
        service.handle(jobHook(208L, "failed", "evt-job-script-retry", "script_failure"));
        assertThat(attempts.requeuedTasks()).contains(withRetry.taskId());
        assertThat(attempts.finishedFailedTasks()).doesNotContain(withRetry.taskId());
    }

    @Test
    void unknownProjectReturns403() {
        ResponseEntity<Void> response = controller.receive(
            JOB_HOOK_SECRET,
            "Job Hook",
            "evt-foreign",
            jobPayload(9999L, 202L, "success", null));
        assertThat(response.getStatusCode()).isEqualTo(FORBIDDEN);
        assertThat(publishQueue.total()).isZero();
        assertThat(deliveries.size()).isZero();
    }

    private Attempt runningAttempt(long jobId) {
        return runningAttempt(jobId, 1, profileWithMaxExternalAttempts(2));
    }

    private Attempt runningAttempt(long jobId, int attemptNo, Profile profile) {
        Attempt attempt = new Attempt(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            jobId,
            attemptNo,
            "RUNNING",
            profile);
        attempts.put(attempt);
        return attempt;
    }

    private Profile profileWithMaxExternalAttempts(int maxExternalAttempts) {
        return new Profile(maxExternalAttempts, false);
    }

    private JobHook jobHook(long jobId, String status, String eventUuid) {
        return jobHook(jobId, status, eventUuid, null);
    }

    private JobHook jobHook(long jobId, String status, String eventUuid, String failureReason) {
        return new JobHook(
            CENTRAL_PROJECT_ID,
            jobId,
            status,
            eventUuid,
            failureReason,
            jobPayload(CENTRAL_PROJECT_ID, jobId, status, failureReason));
    }

    private static String jobPayload(long projectId, long jobId, String status, String failureReason) {
        String reason = failureReason == null ? "null" : "\"" + failureReason + "\"";
        return """
            {"object_kind":"build","project_id":%d,"build_id":%d,"build_status":"%s","build_failure_reason":%s}
            """.formatted(projectId, jobId, status, reason).trim();
    }

    private static final class InMemoryAttempts implements AttemptStore {
        private final Map<UUID, Attempt> byId = new HashMap<>();
        private final Map<Long, Attempt> byJobId = new HashMap<>();
        private final Set<UUID> finishedFailed = new HashSet<>();
        private final Set<UUID> requeued = new HashSet<>();
        private final Map<UUID, UUID> excludedNodeByTask = new HashMap<>();

        void put(Attempt attempt) {
            byId.put(attempt.id(), attempt);
            byJobId.put(attempt.jobId(), attempt);
        }

        Attempt get(UUID id) {
            return byId.get(id);
        }

        Set<UUID> finishedFailedTasks() {
            return Set.copyOf(finishedFailed);
        }

        Set<UUID> requeuedTasks() {
            return Set.copyOf(requeued);
        }

        Set<UUID> excludedNodesFor(UUID taskId) {
            UUID node = excludedNodeByTask.get(taskId);
            return node == null ? Set.of() : Set.of(node);
        }

        @Override
        public Attempt findByJobId(long jobId) {
            return byJobId.get(jobId);
        }

        @Override
        public void update(Attempt attempt) {
            put(attempt);
        }

        @Override
        public void finishTaskAndDefectFailed(UUID taskId, String eventUuid, String failureReason) {
            finishedFailed.add(taskId);
        }

        @Override
        public void requeueTask(UUID taskId, UUID excludeNodeId) {
            requeued.add(taskId);
            excludedNodeByTask.put(taskId, excludeNodeId);
        }

        @Override
        public void releaseNodeSlot(UUID nodeId) {
            // no-op for unit test assertions on attempt/publish
        }
    }

    private static final class InMemoryPublishQueue implements PublishQueue {
        private final Map<UUID, AtomicInteger> counts = new HashMap<>();

        @Override
        public int countFor(UUID attemptId) {
            AtomicInteger count = counts.get(attemptId);
            return count == null ? 0 : count.get();
        }

        @Override
        public void enqueuePending(UUID taskId, UUID attemptId) {
            counts.computeIfAbsent(attemptId, id -> new AtomicInteger()).incrementAndGet();
        }

        int total() {
            return counts.values().stream().mapToInt(AtomicInteger::get).sum();
        }
    }

    private static final class InMemoryJobDeliveries implements JobDeliveryStore {
        private final Map<String, Long> events = new HashMap<>();

        @Override
        public boolean insertIfAbsent(String eventUuid, long jobId, String payloadJson) {
            if (events.containsKey(eventUuid)) {
                return false;
            }
            events.put(eventUuid, jobId);
            return true;
        }

        int size() {
            return events.size();
        }
    }

    private static final class InMemoryAuditSink implements AuditSink {
        private final List<String> actions = new ArrayList<>();

        @Override
        public void unknownJob(long jobId, String eventUuid) {
            actions.add("UNKNOWN_JOB:" + jobId + ":" + eventUuid);
        }
    }
}
