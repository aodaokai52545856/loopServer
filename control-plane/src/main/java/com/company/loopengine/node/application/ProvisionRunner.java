package com.company.loopengine.node.application;

import com.company.loopengine.gitlab.runner.RunnerAdminClient;
import com.company.loopengine.gitlab.runner.RunnerAdminClient.CreatedRunner;
import com.company.loopengine.gitlab.runner.RunnerAdminClient.RunnerAdminRetryableException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Provisions a locked-down central-project Runner for a node in {@code PENDING_RUNNER}.
 * The one-time authentication token is returned only to the caller and never persisted or audited.
 */
public class ProvisionRunner {
    private static final String PENDING_RUNNER = "PENDING_RUNNER";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final RunnerAdminClient runners;
    private final Clock clock;

    public ProvisionRunner(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            RunnerAdminClient runners) {
        this(jdbc, transactionManager, runners, Clock.systemUTC());
    }

    ProvisionRunner(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            RunnerAdminClient runners,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(transactionManager);
        this.runners = Objects.requireNonNull(runners, "runners");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RunnerBootstrap provision(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        NodeRow node = requirePendingRunner(nodeId);
        if (!PENDING_RUNNER.equals(node.state())) {
            throw new IllegalStateException("node is not pending runner provisioning");
        }
        if (node.runnerId() != null) {
            throw new IllegalStateException("node already has a runner");
        }

        CreatedRunner created;
        try {
            created = runners.createProjectRunner(nodeId);
        } catch (RunnerAdminRetryableException ex) {
            throw new RunnerProvisionFailed(ex.getMessage(), true, ex);
        } catch (RuntimeException ex) {
            throw new RunnerProvisionFailed(ex.getMessage(), false, ex);
        }

        try {
            runners.verifyRunner(created.runnerId());
        } catch (RunnerAdminRetryableException ex) {
            throw new RunnerProvisionFailed(ex.getMessage(), true, ex);
        } catch (RuntimeException ex) {
            throw new RunnerProvisionFailed(ex.getMessage(), false, ex);
        }

        persistRunnerId(nodeId, created.runnerId());
        return new RunnerBootstrap(created.runnerId(), created.authenticationToken());
    }

    private NodeRow requirePendingRunner(UUID nodeId) {
        return jdbc.sql("""
            select id, state, runner_id
            from repair_node
            where id = :id
            """)
            .param("id", nodeId)
            .query((rs, rowNum) -> new NodeRow(
                rs.getObject("id", UUID.class),
                rs.getString("state"),
                rs.getObject("runner_id") == null ? null : rs.getLong("runner_id")))
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("node not found"));
    }

    private void persistRunnerId(UUID nodeId, long runnerId) {
        transactions.executeWithoutResult(status -> {
            NodeRow node = jdbc.sql("""
                select id, state, runner_id
                from repair_node
                where id = :id
                for update
                """)
                .param("id", nodeId)
                .query((rs, rowNum) -> new NodeRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("state"),
                    rs.getObject("runner_id") == null ? null : rs.getLong("runner_id")))
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("node not found"));

            if (!PENDING_RUNNER.equals(node.state())) {
                throw new IllegalStateException("node is not pending runner provisioning");
            }
            if (node.runnerId() != null) {
                throw new IllegalStateException("node already has a runner");
            }

            Instant now = clock.instant();
            jdbc.sql("""
                update repair_node
                set runner_id = :runnerId,
                    updated_at = :updatedAt
                where id = :id
                """)
                .param("runnerId", runnerId)
                .param("updatedAt", java.sql.Timestamp.from(now))
                .param("id", nodeId)
                .update();

            // Audit only the runner id — never the one-time authentication token.
            jdbc.sql("""
                insert into audit_log(
                  actor_type, actor_id, action, object_type, object_id, request_id, detail_json)
                values (
                  'NODE', :actorId, 'NODE_RUNNER_PROVISIONED', 'REPAIR_NODE', :objectId, :requestId,
                  cast(:detail as jsonb))
                """)
                .param("actorId", nodeId.toString())
                .param("objectId", nodeId.toString())
                .param("requestId", UUID.randomUUID().toString())
                .param("detail", "{\"runnerId\":" + runnerId + "}")
                .update();
        });
    }

    public record RunnerBootstrap(long runnerId, String authenticationToken) {}

    public static final class RunnerProvisionFailed extends RuntimeException {
        private final boolean retryAction;

        public RunnerProvisionFailed(String message, boolean retryAction, Throwable cause) {
            super(message, cause);
            this.retryAction = retryAction;
        }

        public boolean retryAction() {
            return retryAction;
        }
    }

    private record NodeRow(UUID id, String state, Long runnerId) {}
}
