package com.company.loopengine.scheduling;

import com.company.loopengine.gitlab.pipeline.RepairPipelineClient;
import com.company.loopengine.gitlab.pipeline.RepairPipelineClient.JobSummary;
import com.company.loopengine.gitlab.pipeline.RepairPipelineClient.PipelineAmbiguousException;
import com.company.loopengine.gitlab.pipeline.RepairPipelineClient.PipelineException;
import com.company.loopengine.gitlab.pipeline.RepairPipelineClient.PipelineSummary;
import com.company.loopengine.gitlab.pipeline.RepairPipelineClient.PipelineTerminalException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expires idle ACTIVE reservations after 120 seconds when no attempt has started.
 * Intended to be invoked every 15 seconds by the scheduling layer.
 */
public class ReservationReaper {
    public static final Duration EXTEND_ON_RUNNING = Duration.ofSeconds(60);
    private static final Set<String> CANCELABLE = Set.of(
        "created", "waiting_for_resource", "preparing", "pending", "scheduled", "manual");
    private static final Set<String> RUNNING = Set.of("running", "canceling");
    /** Only confirmed cancelled/failed (or missing via 404) may release capacity. */
    private static final Set<String> RELEASABLE = Set.of("canceled", "cancelled", "failed");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final RepairPipelineClient pipelines;
    private final Clock clock;

    public ReservationReaper(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            RepairPipelineClient pipelines) {
        this(jdbc, transactionManager, pipelines, Clock.systemUTC());
    }

    ReservationReaper(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            RepairPipelineClient pipelines,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(transactionManager);
        this.pipelines = Objects.requireNonNull(pipelines, "pipelines");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void reap() {
        Instant now = clock.instant();
        List<ExpiredReservation> expired = jdbc.sql("""
            select id, task_id, node_id, pipeline_id, expires_at
            from task_reservation
            where state = 'ACTIVE'
              and expires_at < :now
            order by expires_at asc
            """)
            .param("now", Timestamp.from(now))
            .query((rs, rowNum) -> new ExpiredReservation(
                rs.getObject("id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getObject("node_id", UUID.class),
                (Long) rs.getObject("pipeline_id"),
                rs.getTimestamp("expires_at").toInstant()))
            .list();

        for (ExpiredReservation reservation : expired) {
            reapOne(reservation, now);
        }
    }

    private void reapOne(ExpiredReservation reservation, Instant now) {
        // Trigger may have succeeded while pipeline_id persist failed — do not free the slot.
        if (reservation.pipelineId() == null) {
            markExpiryPending(reservation.id());
            return;
        }

        PipelineSummary pipeline;
        List<JobSummary> jobs;
        try {
            pipeline = pipelines.getPipeline(reservation.pipelineId());
            jobs = pipelines.listJobs(reservation.pipelineId());
        } catch (PipelineAmbiguousException ex) {
            markExpiryPending(reservation.id());
            return;
        } catch (PipelineTerminalException ex) {
            // Confirmed missing pipeline may release capacity.
            if (ex.getMessage() != null && ex.getMessage().contains("404")) {
                release(reservation, now);
            } else {
                markExpiryPending(reservation.id());
            }
            return;
        }

        if (hasRunningJob(jobs) || RUNNING.contains(normalize(pipeline.status()))) {
            extend(reservation.id(), now.plus(EXTEND_ON_RUNNING));
            return;
        }

        String status = normalize(pipeline.status());
        if (CANCELABLE.contains(status)) {
            try {
                pipelines.cancelPipeline(reservation.pipelineId());
                pipeline = pipelines.getPipeline(reservation.pipelineId());
                status = normalize(pipeline.status());
            } catch (PipelineAmbiguousException ex) {
                markExpiryPending(reservation.id());
                return;
            } catch (PipelineException ex) {
                markExpiryPending(reservation.id());
                return;
            }
        }

        // success/skipped must not re-queue; only cancelled/failed release the slot.
        if (RELEASABLE.contains(status)) {
            release(reservation, now);
        }
    }

    private static boolean hasRunningJob(List<JobSummary> jobs) {
        for (JobSummary job : jobs) {
            if (RUNNING.contains(normalize(job.status()))) {
                return true;
            }
        }
        return false;
    }

    private void extend(UUID reservationId, Instant newExpiry) {
        transactions.executeWithoutResult(status -> jdbc.sql("""
            update task_reservation
            set expires_at = :expiresAt
            where id = :id and state = 'ACTIVE'
            """)
            .param("expiresAt", Timestamp.from(newExpiry))
            .param("id", reservationId)
            .update());
    }

    private void markExpiryPending(UUID reservationId) {
        transactions.executeWithoutResult(status -> jdbc.sql("""
            update task_reservation
            set state = 'EXPIRY_PENDING'
            where id = :id and state = 'ACTIVE'
            """)
            .param("id", reservationId)
            .update());
    }

    private void release(ExpiredReservation reservation, Instant now) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("""
                update task_reservation
                set state = 'EXPIRED'
                where id = :id and state = 'ACTIVE'
                """)
                .param("id", reservation.id())
                .update();
            jdbc.sql("""
                update repair_node
                set active_slots = greatest(active_slots - 1, 0),
                    updated_at = :now
                where id = :id
                """)
                .param("id", reservation.nodeId())
                .param("now", Timestamp.from(now))
                .update();
            jdbc.sql("""
                update repair_task
                set state = 'QUEUED', updated_at = :now
                where id = :id
                """)
                .param("id", reservation.taskId())
                .param("now", Timestamp.from(now))
                .update();
        });
    }

    private static String normalize(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }

    private record ExpiredReservation(
            UUID id, UUID taskId, UUID nodeId, Long pipelineId, Instant expiresAt) {}
}
