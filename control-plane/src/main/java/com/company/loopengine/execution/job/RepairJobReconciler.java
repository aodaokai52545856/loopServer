package com.company.loopengine.execution.job;

import com.company.loopengine.execution.job.RepairJobService.Attempt;
import com.company.loopengine.execution.job.RepairJobService.AttemptStore;
import com.company.loopengine.execution.job.RepairJobService.JobHook;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded reconciliation for lost Job Hooks.
 * Intended schedule: every {@link #INTERVAL}.
 *
 * <p>Queries stale {@code RUNNING}/{@code ARTIFACT_PENDING} attempts, reads the Job from GitLab,
 * and feeds terminal states into the same {@link RepairJobService} path used by webhooks.
 */
public final class RepairJobReconciler {
    public static final Duration INTERVAL = Duration.ofMinutes(1);
    public static final Duration STALE_AFTER = Duration.ofSeconds(60);
    public static final Duration JOB_NOT_FOUND_TIMEOUT = Duration.ofMinutes(30);
    public static final int MAX_PER_RUN = 100;

    public static final String SUSPECT = "SUSPECT";
    public static final String BLOCKED = "BLOCKED";
    public static final String JOB_NOT_FOUND = "JOB_NOT_FOUND";

    private static final Set<String> TERMINAL = Set.of(
        "success", "failed", "canceled", "cancelled", "timedout");

    private final StaleAttemptQuery staleAttempts;
    private final GitLabJobReader jobs;
    private final NodeLiveness nodes;
    private final AttemptStore attempts;
    private final RepairJobService jobService;
    private final Clock clock;
    private final Map<UUID, Instant> firstMissingAt = new ConcurrentHashMap<>();

    public RepairJobReconciler(
            StaleAttemptQuery staleAttempts,
            GitLabJobReader jobs,
            NodeLiveness nodes,
            AttemptStore attempts,
            RepairJobService jobService,
            Clock clock) {
        this.staleAttempts = Objects.requireNonNull(staleAttempts, "staleAttempts");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.jobService = Objects.requireNonNull(jobService, "jobService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Package-bridge for tests outside {@code execution.job}: constructs the same package-private
     * {@link RepairJobService} wiring used by unit tests.
     */
    public static RepairJobService newJobService(
            AttemptStore attempts,
            RepairJobService.PublishQueue publishQueue,
            RepairJobService.JobDeliveryStore deliveries,
            RepairJobService.AuditSink audit) {
        return new RepairJobService(attempts, publishQueue, deliveries, audit);
    }

    public void runOnce() {
        Instant now = clock.instant();
        Instant olderThan = now.minus(STALE_AFTER);
        List<ReconcileCandidate> candidates = staleAttempts.findStale(olderThan, MAX_PER_RUN);
        int processed = 0;
        for (ReconcileCandidate candidate : candidates) {
            if (processed >= MAX_PER_RUN) {
                return;
            }
            reconcileOne(candidate, now);
            processed++;
        }
    }

    private void reconcileOne(ReconcileCandidate candidate, Instant now) {
        Optional<RemoteJob> remote = jobs.findJob(candidate.projectId(), candidate.jobId());
        if (remote.isEmpty()) {
            handleMissing(candidate, now);
            return;
        }
        firstMissingAt.remove(candidate.attemptId());
        RemoteJob job = remote.get();
        String status = normalize(job.status());
        if (TERMINAL.contains(status)) {
            feedTerminal(candidate, job, status);
            return;
        }
        if (isActive(status) && !nodes.isOnline(candidate.nodeId())) {
            Attempt current = attempts.findByJobId(candidate.jobId());
            if (current != null && !SUSPECT.equals(current.state()) && !BLOCKED.equals(current.state())) {
                attempts.update(current.withState(SUSPECT));
            }
        }
    }

    private void handleMissing(ReconcileCandidate candidate, Instant now) {
        Instant first = firstMissingAt.computeIfAbsent(candidate.attemptId(), id -> now);
        if (Duration.between(first, now).compareTo(JOB_NOT_FOUND_TIMEOUT) < 0) {
            return;
        }
        Attempt current = attempts.findByJobId(candidate.jobId());
        if (current == null || BLOCKED.equals(current.state())) {
            return;
        }
        attempts.update(current.withState(BLOCKED));
        firstMissingAt.remove(candidate.attemptId());
    }

    private void feedTerminal(ReconcileCandidate candidate, RemoteJob job, String status) {
        Attempt current = attempts.findByJobId(candidate.jobId());
        if (current == null) {
            return;
        }
        // SUSPECT attempts must re-enter RUNNING so RepairJobService accepts the terminal hook.
        if (SUSPECT.equals(current.state())) {
            attempts.update(current.withState(RepairJobService.RUNNING));
        }
        String eventUuid = "reconcile:job:" + candidate.jobId() + ":" + status + ":" + candidate.updatedAt();
        String payload = syntheticPayload(candidate.projectId(), candidate.jobId(), status, job.failureReason());
        jobService.handle(new JobHook(
            candidate.projectId(),
            candidate.jobId(),
            status,
            eventUuid,
            job.failureReason(),
            payload));
    }

    private static boolean isActive(String status) {
        return "created".equals(status)
            || "pending".equals(status)
            || "running".equals(status)
            || "waiting_for_resource".equals(status)
            || "preparing".equals(status);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String syntheticPayload(
            long projectId, long jobId, String status, String failureReason) {
        String reasonJson = failureReason == null || failureReason.isBlank()
            ? "null"
            : "\"" + escapeJson(failureReason) + "\"";
        return "{\"object_kind\":\"build\",\"project_id\":" + projectId
            + ",\"build_id\":" + jobId
            + ",\"build_status\":\"" + escapeJson(status) + "\""
            + ",\"build_failure_reason\":" + reasonJson + "}";
    }

    private static String escapeJson(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    @FunctionalInterface
    public interface StaleAttemptQuery {
        /**
         * Returns up to {@code limit} attempts in RUNNING / ARTIFACT_PENDING / SUSPECT whose
         * last update is strictly before {@code olderThan}.
         */
        List<ReconcileCandidate> findStale(Instant olderThan, int limit);
    }

    @FunctionalInterface
    public interface GitLabJobReader {
        /** Empty when the Job is not found (HTTP 404). */
        Optional<RemoteJob> findJob(long projectId, long jobId);
    }

    @FunctionalInterface
    public interface NodeLiveness {
        boolean isOnline(UUID nodeId);
    }

    public record ReconcileCandidate(
            UUID attemptId,
            UUID taskId,
            UUID nodeId,
            long projectId,
            long jobId,
            String state,
            Instant updatedAt) {
        public ReconcileCandidate {
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    public record RemoteJob(long id, String status, String failureReason) {
        public RemoteJob {
            Objects.requireNonNull(status, "status");
        }
    }
}
