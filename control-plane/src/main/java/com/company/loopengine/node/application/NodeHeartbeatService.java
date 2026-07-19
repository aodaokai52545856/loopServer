package com.company.loopengine.node.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class NodeHeartbeatService {
    private static final Pattern CN_PATTERN = Pattern.compile("CN=repair-node:([0-9a-fA-F-]{36})");
    private static final long TEN_GIB = 10L * 1024 * 1024 * 1024;
    private static final Duration OFFLINE_AFTER = Duration.ofSeconds(45);
    private static final String PENDING_CONFIRMATION = "PENDING_CONFIRMATION";
    private static final String PENDING_RUNNER = "PENDING_RUNNER";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final JsonMapper jsonMapper;

    @Autowired
    public NodeHeartbeatService(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            JsonMapper jsonMapper) {
        this(jdbc, Clock.systemUTC(), transactionManager, jsonMapper);
    }

    public NodeHeartbeatService(
            JdbcClient jdbc,
            Clock clock,
            PlatformTransactionManager transactionManager,
            JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jsonMapper = jsonMapper;
    }

    public void confirm(UUID nodeId, ConfirmRequest request) {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(request, "request");
        transactions.executeWithoutResult(status -> confirmInTransaction(nodeId, request));
    }

    public HeartbeatResponse heartbeat(UUID nodeId, HeartbeatRequest request) {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(request, "request");
        return transactions.execute(status -> heartbeatInTransaction(nodeId, request));
    }

    public void setDesired(UUID nodeId, NodeConfig config) {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(config, "config");
        if (config.concurrency() < 1 || config.concurrency() > 10) {
            throw new IllegalArgumentException("concurrency must be between 1 and 10");
        }
        transactions.executeWithoutResult(status -> {
            NodeRow current = requireNode(nodeId);
            long nextRevision = current.desiredRevision() + 1;
            String configJson = writeConfig(config);
            jdbc.sql("""
                update repair_node
                set desired_revision = :revision,
                    desired_config_json = cast(:config as jsonb),
                    concurrency_limit = :concurrency,
                    updated_at = :updatedAt
                where id = :id
                """)
                .param("revision", nextRevision)
                .param("config", configJson)
                .param("concurrency", config.concurrency())
                .param("updatedAt", java.sql.Timestamp.from(clock.instant()))
                .param("id", nodeId)
                .update();
        });
    }

    public NodeSnapshot get(UUID nodeId) {
        NodeRow row = requireNode(nodeId);
        return new NodeSnapshot(
            row.id(),
            row.state(),
            row.enabled(),
            row.desiredRevision(),
            row.appliedRevision(),
            row.config(),
            row.lastHeartbeatAt());
    }

    public AuthenticatedDevice authenticateDevice(X509Certificate certificate) {
        Objects.requireNonNull(certificate, "certificate");
        Instant now = clock.instant();
        if (now.isBefore(certificate.getNotBefore().toInstant())
            || now.isAfter(certificate.getNotAfter().toInstant())) {
            throw new DeviceAuthenticationException("device certificate expired or not yet valid");
        }

        UUID nodeId = extractNodeId(certificate);
        String serial = certificate.getSerialNumber().toString(16).toLowerCase(Locale.ROOT);
        String publicKeySha = sha256Hex(certificate.getPublicKey().getEncoded());

        NodeRow row = jdbc.sql("""
            select id, name, owner_id, public_key_sha256, certificate_serial, state, enabled,
                   desired_revision, applied_revision, desired_config_json::text as config_json,
                   concurrency_limit, active_slots, allowed_projects_json::text as projects_json,
                   last_heartbeat_at
            from repair_node
            where id = :id
            """)
            .param("id", nodeId)
            .query((rs, rowNum) -> mapRow(rs))
            .optional()
            .orElseThrow(() -> new DeviceAuthenticationException("unknown device"));

        if (!row.enabled()) {
            throw new DeviceAuthenticationException("device disabled");
        }
        if (!serialEquals(row.certificateSerial(), serial)) {
            throw new DeviceAuthenticationException("certificate serial mismatch");
        }
        if (!row.publicKeySha256().equalsIgnoreCase(publicKeySha)) {
            throw new DeviceAuthenticationException("public key mismatch");
        }
        return new AuthenticatedDevice(row.id(), row.certificateSerial(), row.publicKeySha256());
    }

    @Scheduled(fixedDelayString = "15000")
    public void sweepNodeStates() {
        transactions.executeWithoutResult(status -> {
            List<NodeRow> nodes = jdbc.sql("""
                select id, name, owner_id, public_key_sha256, certificate_serial, state, enabled,
                       desired_revision, applied_revision, desired_config_json::text as config_json,
                       concurrency_limit, active_slots, allowed_projects_json::text as projects_json,
                       last_heartbeat_at
                from repair_node
                """)
                .query((rs, rowNum) -> mapRow(rs))
                .list();
            Instant now = clock.instant();
            for (NodeRow node : nodes) {
                HeartbeatExtras extras = latestExtras(node.id());
                String next = calculateState(node, extras, now);
                if (!next.equals(node.state())) {
                    jdbc.sql("""
                        update repair_node
                        set state = :state, updated_at = :updatedAt
                        where id = :id
                        """)
                        .param("state", next)
                        .param("updatedAt", java.sql.Timestamp.from(now))
                        .param("id", node.id())
                        .update();
                }
            }
        });
    }

    private void confirmInTransaction(UUID nodeId, ConfirmRequest request) {
        NodeRow node = requireNodeForUpdate(nodeId);
        if (!PENDING_CONFIRMATION.equals(node.state())) {
            throw new IllegalStateException("node is not pending confirmation");
        }
        Set<String> allowed = node.allowedProjects();
        if (!allowed.containsAll(request.acceptedProjects())) {
            throw new IllegalArgumentException("accepted projects exceed invite allow-list");
        }
        String projectsJson = jsonMapper.writeValueAsString(List.copyOf(request.acceptedProjects()));
        String capabilities = request.capabilitiesJson(jsonMapper);
        Instant now = clock.instant();
        jdbc.sql("""
            update repair_node
            set state = :state,
                allowed_projects_json = cast(:projects as jsonb),
                capabilities_json = cast(:capabilities as jsonb),
                updated_at = :updatedAt
            where id = :id
            """)
            .param("state", PENDING_RUNNER)
            .param("projects", projectsJson)
            .param("capabilities", capabilities)
            .param("updatedAt", java.sql.Timestamp.from(now))
            .param("id", nodeId)
            .update();

        jdbc.sql("""
            insert into audit_log(
              actor_type, actor_id, action, object_type, object_id, request_id, detail_json)
            values (
              'NODE', :actorId, 'NODE_CONFIRMED', 'REPAIR_NODE', :objectId, :requestId,
              cast(:detail as jsonb))
            """)
            .param("actorId", nodeId.toString())
            .param("objectId", nodeId.toString())
            .param("requestId", UUID.randomUUID().toString())
            .param("detail", "{\"acceptedProjects\":" + projectsJson + "}")
            .update();
    }

    private HeartbeatResponse heartbeatInTransaction(UUID nodeId, HeartbeatRequest request) {
        NodeRow node = requireNodeForUpdate(nodeId);
        Instant now = clock.instant();
        long applied = Math.max(0, request.appliedRevision());
        if (applied > node.desiredRevision()) {
            applied = node.desiredRevision();
        }

        String metricsJson = writeHeartbeatMetrics(request);
        jdbc.sql("""
            insert into node_heartbeat(node_id, observed_at, metrics_json)
            values (:nodeId, :observedAt, cast(:metrics as jsonb))
            on conflict (node_id, observed_at) do update
              set metrics_json = excluded.metrics_json
            """)
            .param("nodeId", nodeId)
            .param("observedAt", java.sql.Timestamp.from(request.observedAt() == null ? now : request.observedAt()))
            .param("metrics", metricsJson)
            .update();

        String capabilities = writeTools(request.tools());
        HeartbeatExtras extras = new HeartbeatExtras(
            request.runner() == null ? "unknown" : request.runner().status(),
            request.resources() == null ? 0L : request.resources().diskAvailableBytes(),
            request.slots() == null ? 0 : request.slots().active());

        NodeRow updated = new NodeRow(
            node.id(),
            node.name(),
            node.ownerId(),
            node.publicKeySha256(),
            node.certificateSerial(),
            node.state(),
            node.enabled(),
            node.desiredRevision(),
            applied,
            node.config(),
            node.concurrencyLimit(),
            extras.activeSlots(),
            node.allowedProjects(),
            now);

        String nextState = calculateState(updated, extras, now);
        jdbc.sql("""
            update repair_node
            set applied_revision = :applied,
                active_slots = :activeSlots,
                concurrency_limit = :concurrencyLimit,
                capabilities_json = cast(:capabilities as jsonb),
                last_heartbeat_at = :heartbeatAt,
                state = :state,
                updated_at = :updatedAt
            where id = :id
            """)
            .param("applied", applied)
            .param("activeSlots", extras.activeSlots())
            .param("concurrencyLimit",
                request.slots() == null ? node.concurrencyLimit() : request.slots().limit())
            .param("capabilities", capabilities)
            .param("heartbeatAt", java.sql.Timestamp.from(now))
            .param("state", nextState)
            .param("updatedAt", java.sql.Timestamp.from(now))
            .param("id", nodeId)
            .update();

        NodeConfig desired = node.config();
        if (applied >= node.desiredRevision()) {
            return new HeartbeatResponse(node.desiredRevision(), null);
        }
        return new HeartbeatResponse(node.desiredRevision(), desired);
    }

    private String calculateState(NodeRow node, HeartbeatExtras extras, Instant now) {
        if (!node.enabled()) {
            return "DISABLED";
        }
        if (node.config() != null && node.config().drain()) {
            return "DRAINING";
        }
        if (node.lastHeartbeatAt() == null
            || Duration.between(node.lastHeartbeatAt(), now).compareTo(OFFLINE_AFTER) > 0) {
            return "OFFLINE";
        }
        if (extras == null
            || !"online".equalsIgnoreCase(extras.runnerStatus())
            || extras.diskAvailableBytes() < TEN_GIB) {
            return "DEGRADED";
        }
        if (extras.activeSlots() > 0) {
            return "BUSY";
        }
        return "ONLINE";
    }

    private HeartbeatExtras latestExtras(UUID nodeId) {
        return jdbc.sql("""
            select metrics_json::text as metrics
            from node_heartbeat
            where node_id = :nodeId
            order by observed_at desc
            limit 1
            """)
            .param("nodeId", nodeId)
            .query((rs, rowNum) -> parseExtras(rs.getString("metrics")))
            .optional()
            .orElse(new HeartbeatExtras("unknown", 0L, 0));
    }

    private HeartbeatExtras parseExtras(String metricsJson) {
        JsonNode root = jsonMapper.readTree(metricsJson);
        String runner = root.path("runner").path("status").asString("unknown");
        long disk = root.path("resources").path("diskAvailableBytes").asLong(0L);
        int active = root.path("slots").path("active").asInt(0);
        return new HeartbeatExtras(runner, disk, active);
    }

    private NodeRow requireNode(UUID nodeId) {
        return jdbc.sql("""
            select id, name, owner_id, public_key_sha256, certificate_serial, state, enabled,
                   desired_revision, applied_revision, desired_config_json::text as config_json,
                   concurrency_limit, active_slots, allowed_projects_json::text as projects_json,
                   last_heartbeat_at
            from repair_node
            where id = :id
            """)
            .param("id", nodeId)
            .query((rs, rowNum) -> mapRow(rs))
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("node not found"));
    }

    private NodeRow requireNodeForUpdate(UUID nodeId) {
        return jdbc.sql("""
            select id, name, owner_id, public_key_sha256, certificate_serial, state, enabled,
                   desired_revision, applied_revision, desired_config_json::text as config_json,
                   concurrency_limit, active_slots, allowed_projects_json::text as projects_json,
                   last_heartbeat_at
            from repair_node
            where id = :id
            for update
            """)
            .param("id", nodeId)
            .query((rs, rowNum) -> mapRow(rs))
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("node not found"));
    }

    private NodeRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp heartbeat = rs.getTimestamp("last_heartbeat_at");
        return new NodeRow(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("owner_id"),
            rs.getString("public_key_sha256"),
            rs.getString("certificate_serial"),
            rs.getString("state"),
            rs.getBoolean("enabled"),
            rs.getLong("desired_revision"),
            rs.getLong("applied_revision"),
            readConfig(rs.getString("config_json")),
            rs.getInt("concurrency_limit"),
            rs.getInt("active_slots"),
            readProjects(rs.getString("projects_json")),
            heartbeat == null ? null : heartbeat.toInstant());
    }

    private NodeConfig readConfig(String json) {
        JsonNode root = jsonMapper.readTree(json);
        int concurrency = root.path("concurrency").asInt(2);
        boolean drain = root.path("drain").asBoolean(false);
        Set<String> projects = new LinkedHashSet<>();
        JsonNode projectsNode = root.get("allowedProjects");
        if (projectsNode != null && projectsNode.isArray()) {
            projectsNode.forEach(item -> projects.add(item.asString()));
        }
        return new NodeConfig(concurrency, Set.copyOf(projects), drain);
    }

    private Set<String> readProjects(String json) {
        JsonNode node = jsonMapper.readTree(json);
        Set<String> projects = new LinkedHashSet<>();
        if (node.isArray()) {
            node.forEach(item -> projects.add(item.asString()));
        }
        return Set.copyOf(projects);
    }

    private String writeConfig(NodeConfig config) {
        return jsonMapper.writeValueAsString(new DesiredConfigBody(
            config.concurrency(), config.allowedProjects(), config.drain()));
    }

    private String writeHeartbeatMetrics(HeartbeatRequest request) {
        return jsonMapper.writeValueAsString(request);
    }

    private String writeTools(HeartbeatRequest.Tools tools) {
        if (tools == null) {
            return "{}";
        }
        return jsonMapper.writeValueAsString(tools);
    }

    static UUID extractNodeId(X509Certificate certificate) {
        String dn = certificate.getSubjectX500Principal().getName();
        Matcher matcher = CN_PATTERN.matcher(dn);
        if (!matcher.find()) {
            throw new DeviceAuthenticationException("certificate CN is not a repair-node subject");
        }
        return UUID.fromString(matcher.group(1));
    }

    private static boolean serialEquals(String stored, String presented) {
        String a = stored == null ? "" : stored.replaceFirst("^0+", "").toLowerCase(Locale.ROOT);
        String b = presented == null ? "" : presented.replaceFirst("^0+", "").toLowerCase(Locale.ROOT);
        if (a.isEmpty()) {
            a = "0";
        }
        if (b.isEmpty()) {
            b = "0";
        }
        return a.equals(b);
    }

    private static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private record NodeRow(
        UUID id,
        String name,
        String ownerId,
        String publicKeySha256,
        String certificateSerial,
        String state,
        boolean enabled,
        long desiredRevision,
        long appliedRevision,
        NodeConfig config,
        int concurrencyLimit,
        int activeSlots,
        Set<String> allowedProjects,
        Instant lastHeartbeatAt) {}

    private record HeartbeatExtras(String runnerStatus, long diskAvailableBytes, int activeSlots) {}

    private record DesiredConfigBody(int concurrency, Set<String> allowedProjects, boolean drain) {}

    public record NodeConfig(int concurrency, Set<String> allowedProjects, boolean drain) {}

    public record ConfirmRequest(Set<String> acceptedProjects, java.util.Map<String, Object> capabilities) {
        public String capabilitiesJson(JsonMapper mapper) {
            if (capabilities == null || capabilities.isEmpty()) {
                return "{}";
            }
            return mapper.writeValueAsString(capabilities);
        }
    }

    public record HeartbeatRequest(
        Instant observedAt,
        long appliedRevision,
        Runner runner,
        Slots slots,
        Resources resources,
        Tools tools,
        List<String> activeAttemptIds,
        String lastError) {
        public record Runner(String status, String version) {}
        public record Slots(int active, int limit) {}
        public record Resources(double cpuPercent, long memoryAvailableBytes, long diskAvailableBytes) {}
        public record Tools(
            String os,
            String arch,
            String java,
            String maven,
            String node,
            String pnpm,
            String opencode) {}
    }

    public record HeartbeatResponse(long desiredRevision, NodeConfig config) {}

    public record NodeSnapshot(
        UUID id,
        String state,
        boolean enabled,
        long desiredRevision,
        long appliedRevision,
        NodeConfig config,
        Instant lastHeartbeatAt) {}

    public record AuthenticatedDevice(UUID nodeId, String certificateSerial, String publicKeySha256) {}

    public static final class DeviceAuthenticationException extends RuntimeException {
        public DeviceAuthenticationException(String message) {
            super(message);
        }
    }
}

@Configuration
@EnableScheduling
class NodeSchedulingConfiguration {}
