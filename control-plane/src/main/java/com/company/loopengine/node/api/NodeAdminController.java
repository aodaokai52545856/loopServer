package com.company.loopengine.node.api;

import com.company.loopengine.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@RestController
@ConditionalOnMissingBean(name = "adminInviteController")
public class NodeAdminController {
    private static final Duration DEFAULT_INVITE_TTL = Duration.ofHours(24);
    private static final int REASON_MIN = 10;
    private static final int REASON_MAX = 500;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public NodeAdminController(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            JsonMapper jsonMapper) {
        this(jdbc, transactionManager, jsonMapper, Clock.systemUTC());
    }

    NodeAdminController(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            JsonMapper jsonMapper,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(transactionManager);
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping("/api/admin/node-invites")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<Map<String, Object>> createInvite(
            Authentication authentication,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        JsonNode payload = body == null || body.isNull() ? jsonMapper.createObjectNode() : body;
        String reason = optionalReason(payload);
        Instant expiresAt = parseExpiresAt(payload);
        List<String> projects = parseProjects(payload);
        String requestId = requestId(request);

        Map<String, Object> created = transactions.execute(status -> {
            byte[] raw = new byte[32];
            secureRandom.nextBytes(raw);
            String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            UUID id = UUID.randomUUID();
            String projectsJson = jsonMapper.writeValueAsString(projects);
            jdbc.sql("""
                insert into node_invite(
                  id, code_hash, allowed_projects_json, expires_at, created_by)
                values (
                  :id, :codeHash, cast(:projects as jsonb), :expiresAt, :createdBy)
                """)
                    .param("id", id)
                    .param("codeHash", sha256Hex(code))
                    .param("projects", projectsJson)
                    .param("expiresAt", Timestamp.from(expiresAt))
                    .param("createdBy", authentication.getName())
                    .update();

            long auditId = writeAudit(
                    authentication,
                    "NODE_INVITE_CREATED",
                    "NODE_INVITE",
                    id.toString(),
                    requestId,
                    Map.of("reason", reason, "expiresAt", expiresAt.toString()));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", id.toString());
            response.put("code", code);
            response.put("expiresAt", expiresAt.toString());
            response.put("joinCommand", "repair-node join --code " + code);
            response.put("auditId", auditId);
            return response;
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/api/nodes/{nodeId}/drain")
    @PreAuthorize("@projectAuthorization.canDrainNode(authentication, #nodeId)")
    ResponseEntity<Map<String, Object>> drain(
            Authentication authentication,
            @PathVariable String nodeId,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        return mutateNode(authentication, nodeId, body, request, "NODE_DRAIN", true, false, null);
    }

    @PostMapping("/api/nodes/{nodeId}/resume")
    @PreAuthorize("@projectAuthorization.canDrainNode(authentication, #nodeId)")
    ResponseEntity<Map<String, Object>> resume(
            Authentication authentication,
            @PathVariable String nodeId,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        return mutateNode(authentication, nodeId, body, request, "NODE_RESUME", false, false, null);
    }

    @PostMapping("/api/nodes/{nodeId}/disable")
    @PreAuthorize("@projectAuthorization.canDrainNode(authentication, #nodeId)")
    ResponseEntity<Map<String, Object>> disable(
            Authentication authentication,
            @PathVariable String nodeId,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        return mutateNode(authentication, nodeId, body, request, "NODE_DISABLE", true, true, null);
    }

    @PatchMapping("/api/nodes/{nodeId}/configuration")
    @PreAuthorize("@projectAuthorization.canDrainNode(authentication, #nodeId)")
    ResponseEntity<Map<String, Object>> configure(
            Authentication authentication,
            @PathVariable String nodeId,
            @RequestBody JsonNode body,
            HttpServletRequest request) {
        Objects.requireNonNull(body, "body");
        String reason = requireReason(body);
        String requestId = requestId(request);
        Map<String, Object> result = transactions.execute(status -> {
            NodeRow node = requireNode(nodeId);
            int concurrency = body.path("concurrency").asInt(node.concurrencyLimit());
            if (concurrency < 1 || concurrency > 10) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "concurrency must be 1-10");
            }
            List<String> projects = parseProjects(body);
            boolean drain = body.path("drain").asBoolean(node.drain());
            ObjectNode config = jsonMapper.createObjectNode();
            config.put("concurrency", concurrency);
            config.set("allowedProjects", jsonMapper.valueToTree(projects));
            config.put("drain", drain);
            if (node.certificateRotationRequested()) {
                config.put("certificateRotationRequested", true);
            }
            String configJson = jsonMapper.writeValueAsString(config);
            String projectsJson = jsonMapper.writeValueAsString(projects);
            long nextRevision = node.desiredRevision() + 1;
            Instant now = clock.instant();
            jdbc.sql("""
                update repair_node
                set concurrency_limit = :concurrency,
                    allowed_projects_json = cast(:projects as jsonb),
                    desired_config_json = cast(:config as jsonb),
                    desired_revision = :revision,
                    updated_at = :updatedAt
                where id = :id
                """)
                    .param("concurrency", concurrency)
                    .param("projects", projectsJson)
                    .param("config", configJson)
                    .param("revision", nextRevision)
                    .param("updatedAt", Timestamp.from(now))
                    .param("id", node.id())
                    .update();
            long auditId = writeAudit(
                    authentication,
                    "NODE_CONFIGURATION_UPDATED",
                    "REPAIR_NODE",
                    node.id().toString(),
                    requestId,
                    Map.of("reason", reason, "desiredRevision", nextRevision));
            return Map.<String, Object>of(
                    "nodeId", node.id().toString(),
                    "desiredRevision", nextRevision,
                    "auditId", auditId);
        });
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/nodes/{nodeId}/certificate-rotation")
    @PreAuthorize("@projectAuthorization.canDrainNode(authentication, #nodeId)")
    ResponseEntity<Map<String, Object>> requestCertificateRotation(
            Authentication authentication,
            @PathVariable String nodeId,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        JsonNode payload = body == null || body.isNull() ? jsonMapper.createObjectNode() : body;
        String reason = requireReason(payload);
        String requestId = requestId(request);
        Map<String, Object> result = transactions.execute(status -> {
            NodeRow node = requireNode(nodeId);
            ObjectNode config = jsonMapper.createObjectNode();
            config.put("concurrency", node.concurrencyLimit());
            config.set("allowedProjects", jsonMapper.valueToTree(node.allowedProjects()));
            config.put("drain", node.drain());
            config.put("certificateRotationRequested", true);
            String configJson = jsonMapper.writeValueAsString(config);
            long nextRevision = node.desiredRevision() + 1;
            Instant now = clock.instant();
            jdbc.sql("""
                update repair_node
                set desired_config_json = cast(:config as jsonb),
                    desired_revision = :revision,
                    updated_at = :updatedAt
                where id = :id
                """)
                    .param("config", configJson)
                    .param("revision", nextRevision)
                    .param("updatedAt", Timestamp.from(now))
                    .param("id", node.id())
                    .update();
            long auditId = writeAudit(
                    authentication,
                    "NODE_CERTIFICATE_ROTATION_REQUESTED",
                    "REPAIR_NODE",
                    node.id().toString(),
                    requestId,
                    Map.of("reason", reason, "desiredRevision", nextRevision));
            return Map.<String, Object>of(
                    "nodeId", node.id().toString(),
                    "desiredRevision", nextRevision,
                    "auditId", auditId);
        });
        return ResponseEntity.accepted().body(result);
    }

    private ResponseEntity<Map<String, Object>> mutateNode(
            Authentication authentication,
            String nodeId,
            JsonNode body,
            HttpServletRequest request,
            String action,
            boolean drain,
            boolean disable,
            Integer concurrencyOverride) {
        JsonNode payload = body == null || body.isNull() ? jsonMapper.createObjectNode() : body;
        String reason = optionalReason(payload);
        String requestId = requestId(request);
        Map<String, Object> result = transactions.execute(status -> {
            NodeRow node = requireNode(nodeId);
            Instant now = clock.instant();
            ObjectNode config = jsonMapper.createObjectNode();
            int concurrency =
                    concurrencyOverride == null ? node.concurrencyLimit() : concurrencyOverride;
            config.put("concurrency", concurrency);
            config.set("allowedProjects", jsonMapper.valueToTree(node.allowedProjects()));
            config.put("drain", drain);
            if (node.certificateRotationRequested()) {
                config.put("certificateRotationRequested", true);
            }
            String configJson = jsonMapper.writeValueAsString(config);
            long nextRevision = node.desiredRevision() + 1;
            String nextState = disable ? "DISABLED" : node.state();
            jdbc.sql("""
                update repair_node
                set desired_config_json = cast(:config as jsonb),
                    desired_revision = :revision,
                    enabled = :enabled,
                    state = case when :disable then 'DISABLED' else state end,
                    updated_at = :updatedAt
                where id = :id
                """)
                    .param("config", configJson)
                    .param("revision", nextRevision)
                    .param("enabled", !disable)
                    .param("disable", disable)
                    .param("updatedAt", Timestamp.from(now))
                    .param("id", node.id())
                    .update();

            if (disable) {
                jdbc.sql("""
                    update node_certificate
                    set status = 'REVOKED', revoked_at = :revokedAt
                    where node_id = :nodeId and status <> 'REVOKED'
                    """)
                        .param("revokedAt", Timestamp.from(now))
                        .param("nodeId", node.id())
                        .update();
                if (node.runnerId() != null) {
                    jdbc.sql("""
                        insert into outbox_event(
                          id, aggregate_type, aggregate_id, event_type, payload_json, occurred_at)
                        values (
                          :id, 'REPAIR_NODE', :aggregateId, 'gitlab.runner.delete',
                          cast(:payload as jsonb), :occurredAt)
                        """)
                            .param("id", UUID.randomUUID())
                            .param("aggregateId", node.id().toString())
                            .param(
                                    "payload",
                                    jsonMapper.writeValueAsString(
                                            Map.of("runnerId", node.runnerId(), "nodeId", node.id().toString())))
                            .param("occurredAt", Timestamp.from(now))
                            .update();
                }
            }

            long auditId = writeAudit(
                    authentication,
                    action,
                    "REPAIR_NODE",
                    node.id().toString(),
                    requestId,
                    Map.of(
                            "reason",
                            reason,
                            "drain",
                            drain,
                            "disable",
                            disable,
                            "desiredRevision",
                            nextRevision,
                            "state",
                            nextState));
            return Map.<String, Object>of(
                    "nodeId", node.id().toString(),
                    "desiredRevision", nextRevision,
                    "auditId", auditId);
        });
        return ResponseEntity.accepted().body(result);
    }

    private NodeRow requireNode(String nodeId) {
        return jdbc.sql("""
                select id, name, owner_id, state, enabled, desired_revision, concurrency_limit,
                       runner_id, desired_config_json::text as desired_config_json,
                       allowed_projects_json::text as allowed_projects_json
                from repair_node
                where name = :nodeId or cast(id as varchar) = :nodeId
                for update
                """)
                .param("nodeId", nodeId)
                .query((rs, rowNum) -> {
                    String configJson = rs.getString("desired_config_json");
                    boolean drain = false;
                    boolean rotation = false;
                    try {
                        JsonNode config = jsonMapper.readTree(configJson);
                        drain = config.path("drain").asBoolean(false);
                        rotation = config.path("certificateRotationRequested").asBoolean(false);
                    } catch (RuntimeException ignored) {
                        // keep defaults
                    }
                    List<String> projects;
                    try {
                        projects = jsonMapper.readValue(
                                rs.getString("allowed_projects_json"),
                                jsonMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    } catch (RuntimeException ex) {
                        projects = List.of();
                    }
                    Long runnerId = rs.getObject("runner_id") == null ? null : rs.getLong("runner_id");
                    return new NodeRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("name"),
                            rs.getString("owner_id"),
                            rs.getString("state"),
                            rs.getBoolean("enabled"),
                            rs.getLong("desired_revision"),
                            rs.getInt("concurrency_limit"),
                            runnerId,
                            projects,
                            drain,
                            rotation);
                })
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

    private static String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(CorrelationIdFilter.HEADER);
        if (attr instanceof String value && !value.isBlank()) {
            return value;
        }
        return UUID.randomUUID().toString();
    }

    private static String requireReason(JsonNode body) {
        String reason = body.path("reason").asString("").trim();
        if (reason.length() < REASON_MIN || reason.length() > REASON_MAX) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "reason must be 10-500 characters");
        }
        return reason;
    }

    private static String optionalReason(JsonNode body) {
        String reason = body.path("reason").asString("").trim();
        if (reason.isEmpty()) {
            return "authorized administrative action";
        }
        if (reason.length() < REASON_MIN || reason.length() > REASON_MAX) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "reason must be 10-500 characters");
        }
        return reason;
    }

    private Instant parseExpiresAt(JsonNode body) {
        String raw = body.path("expiresAt").asString("").trim();
        if (raw.isEmpty()) {
            return clock.instant().plus(DEFAULT_INVITE_TTL);
        }
        try {
            return Instant.parse(raw);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid expiresAt");
        }
    }

    private List<String> parseProjects(JsonNode body) {
        JsonNode projects = body.get("allowedProjects");
        if (projects == null || projects.isNull()) {
            projects = body.get("allowed_projects");
        }
        if (projects == null || projects.isNull() || !projects.isArray()) {
            return List.of();
        }
        return jsonMapper.convertValue(
                projects, jsonMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record NodeRow(
            UUID id,
            String name,
            String ownerId,
            String state,
            boolean enabled,
            long desiredRevision,
            int concurrencyLimit,
            Long runnerId,
            List<String> allowedProjects,
            boolean drain,
            boolean certificateRotationRequested) {}
}
