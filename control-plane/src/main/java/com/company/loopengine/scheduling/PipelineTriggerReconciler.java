package com.company.loopengine.scheduling;

import com.company.loopengine.gitlab.pipeline.RepairPipelineClient;
import com.company.loopengine.gitlab.pipeline.RepairPipelineClient.PipelineAmbiguousException;
import com.company.loopengine.gitlab.pipeline.RepairPipelineClient.PipelineSummary;
import com.company.loopengine.gitlab.pipeline.RepairPipelineClient.PipelineTerminalException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciles ambiguous Pipeline triggers without ever sending a second trigger.
 */
public class PipelineTriggerReconciler {
    public static final Duration UNKNOWN_GRACE = Duration.ofMinutes(2);
    private static final int REQUIRED_SUCCESSFUL_QUERIES = 2;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final RepairPipelineClient pipelines;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, AtomicInteger> successfulQueries = new ConcurrentHashMap<>();

    public PipelineTriggerReconciler(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            RepairPipelineClient pipelines) {
        this(jdbc, transactionManager, pipelines, Clock.systemUTC());
    }

    PipelineTriggerReconciler(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            RepairPipelineClient pipelines,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(transactionManager);
        this.pipelines = Objects.requireNonNull(pipelines, "pipelines");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void reconcile() {
        List<UnknownReservation> unknowns = jdbc.sql("""
            select id, task_id, node_id, created_at
            from task_reservation
            where state = 'TRIGGER_UNKNOWN'
            order by created_at asc
            """)
            .query((rs, rowNum) -> new UnknownReservation(
                rs.getObject("id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getObject("node_id", UUID.class),
                rs.getTimestamp("created_at").toInstant()))
            .list();

        for (UnknownReservation reservation : unknowns) {
            reconcileOne(reservation);
        }
    }

    private void reconcileOne(UnknownReservation reservation) {
        Long matchedPipelineId;
        try {
            matchedPipelineId = findPipelineForReservation(reservation.id());
            successfulQueries
                .computeIfAbsent(reservation.id(), id -> new AtomicInteger())
                .incrementAndGet();
        } catch (PipelineAmbiguousException ex) {
            return;
        } catch (PipelineTerminalException ex) {
            // Treat missing project/API as a completed query that found nothing.
            matchedPipelineId = null;
            successfulQueries
                .computeIfAbsent(reservation.id(), id -> new AtomicInteger())
                .incrementAndGet();
        }

        if (matchedPipelineId != null) {
            restoreActive(reservation.id(), matchedPipelineId);
            successfulQueries.remove(reservation.id());
            return;
        }

        Instant now = clock.instant();
        int queries = successfulQueries.getOrDefault(reservation.id(), new AtomicInteger()).get();
        if (reservation.createdAt().plus(UNKNOWN_GRACE).isBefore(now)
                && queries >= REQUIRED_SUCCESSFUL_QUERIES) {
            releaseReservation(reservation);
            successfulQueries.remove(reservation.id());
        }
    }

    private Long findPipelineForReservation(UUID reservationId) {
        List<PipelineSummary> recent = pipelines.listRecentPipelines(50);
        for (PipelineSummary pipeline : recent) {
            Map<String, String> variables = pipelines.getPipelineVariables(pipeline.id());
            if (reservationId.toString().equals(variables.get("LOOP_RESERVATION_ID"))) {
                return pipeline.id();
            }
        }
        return null;
    }

    private void restoreActive(UUID reservationId, long pipelineId) {
        transactions.executeWithoutResult(status -> jdbc.sql("""
            update task_reservation
            set state = 'ACTIVE',
                pipeline_id = :pipelineId
            where id = :id and state = 'TRIGGER_UNKNOWN'
            """)
            .param("pipelineId", pipelineId)
            .param("id", reservationId)
            .update());
    }

    private void releaseReservation(UnknownReservation reservation) {
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            jdbc.sql("""
                update task_reservation
                set state = 'EXPIRED'
                where id = :id and state = 'TRIGGER_UNKNOWN'
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

    private record UnknownReservation(UUID id, UUID taskId, UUID nodeId, Instant createdAt) {}
}
