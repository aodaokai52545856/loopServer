package com.company.loopengine.scheduling;

import com.company.loopengine.gitlab.pipeline.RepairPipelineClient;
import com.company.loopengine.gitlab.pipeline.RepairPipelineClient.PipelineAmbiguousException;
import com.company.loopengine.gitlab.pipeline.RepairPipelineClient.PipelineTerminalException;
import com.company.loopengine.scheduling.NodeMatcher.NodeCandidate;
import com.company.loopengine.scheduling.NodeMatcher.TaskRequirements;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Atomically reserves node capacity before triggering an external central Pipeline.
 */
public class Scheduler {
    public static final Duration RESERVATION_TTL = Duration.ofSeconds(120);

    private static final Pattern STRING_ARRAY = Pattern.compile("\"((?:\\\\.|[^\\\\\"])*)\"");
    private static final Pattern NUMBER_FIELD = Pattern.compile("\"%s\"\\s*:\\s*(-?\\d+)");
    private static final Pattern STRING_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"])*)\"");
    private static final Pattern BOOL_FIELD = Pattern.compile("\"%s\"\\s*:\\s*(true|false)");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final RepairPipelineClient pipelines;
    private final NodeMatcher matcher;
    private final Clock clock;

    public Scheduler(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            RepairPipelineClient pipelines,
            NodeMatcher matcher) {
        this(jdbc, transactionManager, pipelines, matcher, Clock.systemUTC());
    }

    Scheduler(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            RepairPipelineClient pipelines,
            NodeMatcher matcher,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(transactionManager);
        this.pipelines = Objects.requireNonNull(pipelines, "pipelines");
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<ScheduleResult> scheduleOne() {
        Optional<ReservationClaim> claim = transactions.execute(status -> claimReservation());
        if (claim == null || claim.isEmpty()) {
            return Optional.empty();
        }
        ReservationClaim reserved = claim.get();
        try {
            long pipelineId = pipelines.triggerRepairPipeline(
                reserved.taskId(),
                reserved.reservationId(),
                reserved.nodeId(),
                reserved.runnerTag());
            persistPipelineId(reserved.reservationId(), pipelineId);
            return Optional.of(new ScheduleResult(
                reserved.taskId(), reserved.reservationId(), reserved.nodeId(), pipelineId));
        } catch (PipelineTerminalException ex) {
            compensateTerminalFailure(reserved);
            return Optional.empty();
        } catch (PipelineAmbiguousException ex) {
            markTriggerUnknown(reserved.reservationId());
            return Optional.of(new ScheduleResult(
                reserved.taskId(), reserved.reservationId(), reserved.nodeId(), null));
        }
    }

    private Optional<ReservationClaim> claimReservation() {
        Optional<UUID> taskId = jdbc.sql("""
            select id from repair_task
            where state = 'QUEUED'
            order by priority asc, created_at asc
            for update skip locked
            limit 1
            """)
            .query(UUID.class)
            .optional();
        if (taskId.isEmpty()) {
            return Optional.empty();
        }

        TaskRow task = loadTask(taskId.get());
        TaskRequirements requirements = toRequirements(task);
        List<NodeCandidate> ranked = matcher.rank(requirements, loadCandidates());
        Instant now = clock.instant();
        Instant expiresAt = now.plus(RESERVATION_TTL);

        for (NodeCandidate candidate : ranked) {
            Optional<LockedNode> locked = lockNode(candidate.id());
            if (locked.isEmpty()) {
                continue;
            }
            LockedNode node = locked.get();
            if (node.activeSlots() >= node.concurrencyLimit()) {
                continue;
            }
            UUID reservationId = UUID.randomUUID();
            jdbc.sql("""
                update repair_node
                set active_slots = active_slots + 1,
                    updated_at = :now
                where id = :id
                """)
                .param("id", node.id())
                .param("now", Timestamp.from(now))
                .update();
            jdbc.sql("""
                insert into task_reservation(id, task_id, node_id, pipeline_id, expires_at, state, created_at)
                values (:id, :taskId, :nodeId, null, :expiresAt, 'ACTIVE', :createdAt)
                """)
                .param("id", reservationId)
                .param("taskId", task.id())
                .param("nodeId", node.id())
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("createdAt", Timestamp.from(now))
                .update();
            jdbc.sql("""
                update repair_task
                set state = 'RESERVED', updated_at = :now
                where id = :id
                """)
                .param("id", task.id())
                .param("now", Timestamp.from(now))
                .update();
            return Optional.of(new ReservationClaim(
                task.id(), reservationId, node.id(), node.runnerTag()));
        }
        return Optional.empty();
    }

    private void persistPipelineId(UUID reservationId, long pipelineId) {
        transactions.executeWithoutResult(status -> jdbc.sql("""
            update task_reservation
            set pipeline_id = :pipelineId
            where id = :id and state = 'ACTIVE'
            """)
            .param("pipelineId", pipelineId)
            .param("id", reservationId)
            .update());
    }

    private void compensateTerminalFailure(ReservationClaim claim) {
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            jdbc.sql("""
                update task_reservation
                set state = 'EXPIRED'
                where id = :id
                """)
                .param("id", claim.reservationId())
                .update();
            jdbc.sql("""
                update repair_node
                set active_slots = greatest(active_slots - 1, 0),
                    updated_at = :now
                where id = :id
                """)
                .param("id", claim.nodeId())
                .param("now", Timestamp.from(now))
                .update();
            jdbc.sql("""
                update repair_task
                set state = 'QUEUED', updated_at = :now
                where id = :id
                """)
                .param("id", claim.taskId())
                .param("now", Timestamp.from(now))
                .update();
        });
    }

    private void markTriggerUnknown(UUID reservationId) {
        transactions.executeWithoutResult(status -> jdbc.sql("""
            update task_reservation
            set state = 'TRIGGER_UNKNOWN'
            where id = :id
            """)
            .param("id", reservationId)
            .update());
    }

    private TaskRow loadTask(UUID taskId) {
        return jdbc.sql("""
            select id, project_key, profile_snapshot_json::text as snapshot, requested_node_id
            from repair_task
            where id = :id
            """)
            .param("id", taskId)
            .query((rs, rowNum) -> new TaskRow(
                rs.getObject("id", UUID.class),
                rs.getString("project_key"),
                rs.getString("snapshot"),
                rs.getObject("requested_node_id", UUID.class)))
            .single();
    }

    private Optional<LockedNode> lockNode(UUID nodeId) {
        return jdbc.sql("""
            select id, runner_tag, active_slots, concurrency_limit
            from repair_node
            where id = :id
            for update
            """)
            .param("id", nodeId)
            .query((rs, rowNum) -> new LockedNode(
                rs.getObject("id", UUID.class),
                rs.getString("runner_tag"),
                rs.getInt("active_slots"),
                rs.getInt("concurrency_limit")))
            .optional();
    }

    private List<NodeCandidate> loadCandidates() {
        return jdbc.sql("""
            select id, name, state, enabled, owner_id, runner_tag,
                   active_slots, concurrency_limit,
                   allowed_projects_json::text as projects_json,
                   capabilities_json::text as capabilities_json,
                   (select max(created_at) from task_reservation tr where tr.node_id = repair_node.id) as last_assigned
            from repair_node
            """)
            .query((rs, rowNum) -> {
                String caps = rs.getString("capabilities_json");
                Timestamp lastAssigned = rs.getTimestamp("last_assigned");
                return new NodeCandidate(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("state"),
                    rs.getBoolean("enabled"),
                    readStringSet(rs.getString("projects_json")),
                    rs.getString("owner_id"),
                    readString(caps, "os").orElse("linux"),
                    readString(caps, "arch").orElse("amd64"),
                    readInt(caps, "java").orElse(21),
                    readLong(caps, "memoryAvailableBytes").orElse(0L),
                    readLong(caps, "diskAvailableBytes").orElse(0L),
                    rs.getInt("active_slots"),
                    rs.getInt("concurrency_limit"),
                    readBoolean(caps, "projectOwner").orElse(false),
                    readBoolean(caps, "hasRepoCache").orElse(false),
                    readInt(caps, "failureRatePermille").orElse(500),
                    lastAssigned == null ? Instant.EPOCH : lastAssigned.toInstant());
            })
            .list();
    }

    private static TaskRequirements toRequirements(TaskRow task) {
        String snapshot = task.snapshot() == null ? "{}" : task.snapshot();
        Set<String> allowedOs = readStringSet(extractArray(snapshot, "allowedOs"));
        Set<String> allowedArch = readStringSet(extractArray(snapshot, "allowedArch"));
        if (allowedArch.isEmpty()) {
            allowedArch = Set.of("amd64");
        }
        int minJava = parseMinJava(snapshot);
        long minMemory = readLong(snapshot, "minMemoryBytes").orElse(0L);
        long minDisk = readLong(snapshot, "minDiskBytes").orElse(0L);
        Set<UUID> allowedNodeIds = new HashSet<>();
        for (String raw : readStringSet(extractArray(snapshot, "allowedNodeIds"))) {
            try {
                allowedNodeIds.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // skip malformed ids
            }
        }
        Set<String> allowedOwners = readStringSet(extractArray(snapshot, "allowedNodeOwnerIds"));
        return new TaskRequirements(
            task.projectKey(),
            allowedOs,
            allowedArch,
            minJava,
            minMemory,
            minDisk,
            task.requestedNodeId(),
            allowedNodeIds,
            allowedOwners);
    }

    private static int parseMinJava(String snapshot) {
        Optional<String> javaConstraint = readNestedString(snapshot, "requiredTools", "java");
        if (javaConstraint.isEmpty()) {
            return 0;
        }
        String raw = javaConstraint.get().replace(">=", "").replace(">", "").trim();
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String extractArray(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\[[^\\]]*\\])");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : "[]";
    }

    private static Set<String> readStringSet(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return Set.of();
        }
        Set<String> values = new HashSet<>();
        Matcher matcher = STRING_ARRAY.matcher(jsonArray);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return Set.copyOf(values);
    }

    private static Optional<String> readString(String json, String field) {
        Matcher matcher = Pattern.compile(STRING_FIELD.pattern().formatted(field)).matcher(json == null ? "" : json);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Optional<String> readNestedString(String json, String parent, String field) {
        Pattern parentBlock = Pattern.compile("\"" + parent + "\"\\s*:\\s*\\{([^{}]*)\\}");
        Matcher block = parentBlock.matcher(json == null ? "" : json);
        if (!block.find()) {
            return Optional.empty();
        }
        return readString("{" + block.group(1) + "}", field);
    }

    private static Optional<Integer> readInt(String json, String field) {
        Matcher matcher = Pattern.compile(NUMBER_FIELD.pattern().formatted(field)).matcher(json == null ? "" : json);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(matcher.group(1)));
    }

    private static Optional<Long> readLong(String json, String field) {
        Matcher matcher = Pattern.compile(NUMBER_FIELD.pattern().formatted(field)).matcher(json == null ? "" : json);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(matcher.group(1)));
    }

    private static Optional<Boolean> readBoolean(String json, String field) {
        Matcher matcher = Pattern.compile(BOOL_FIELD.pattern().formatted(field)).matcher(json == null ? "" : json);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(Boolean.parseBoolean(matcher.group(1)));
    }

    public record ScheduleResult(UUID taskId, UUID reservationId, UUID nodeId, Long pipelineId) {}

    private record ReservationClaim(UUID taskId, UUID reservationId, UUID nodeId, String runnerTag) {}

    private record TaskRow(UUID id, String projectKey, String snapshot, UUID requestedNodeId) {}

    private record LockedNode(UUID id, String runnerTag, int activeSlots, int concurrencyLimit) {}
}
