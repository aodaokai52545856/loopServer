package com.company.loopengine.node.enrollment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
public class NodeEnrollmentService {
    private static final String PENDING_CONFIRMATION = "PENDING_CONFIRMATION";
    private static final int DEFAULT_CONCURRENCY = 2;

    private final JdbcClient jdbc;
    private final DeviceCertificateAuthority certificateAuthority;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final JsonMapper jsonMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public NodeEnrollmentService(
            JdbcClient jdbc,
            DeviceCertificateAuthority certificateAuthority,
            PlatformTransactionManager transactionManager,
            JsonMapper jsonMapper) {
        this(jdbc, certificateAuthority, Clock.systemUTC(), transactionManager, jsonMapper);
    }

    NodeEnrollmentService(
            JdbcClient jdbc,
            DeviceCertificateAuthority certificateAuthority,
            Clock clock,
            PlatformTransactionManager transactionManager,
            JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.certificateAuthority = certificateAuthority;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jsonMapper = jsonMapper;
    }

    public String create(Set<String> allowedProjects, Instant expiresAt, String createdBy) {
        byte[] raw = new byte[32];
        secureRandom.nextBytes(raw);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String codeHash = sha256Hex(code);
        UUID id = UUID.randomUUID();
        String projectsJson = jsonMapper.writeValueAsString(List.copyOf(allowedProjects));
        jdbc.sql("""
            insert into node_invite(
              id, code_hash, allowed_projects_json, expires_at, created_by)
            values (
              :id, :codeHash, cast(:projects as jsonb), :expiresAt, :createdBy)
            """)
            .param("id", id)
            .param("codeHash", codeHash)
            .param("projects", projectsJson)
            .param("expiresAt", java.sql.Timestamp.from(expiresAt))
            .param("createdBy", createdBy)
            .update();
        return code;
    }

    public Enrollment enroll(String code, EnrollmentRequest request) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(request, "request");
        return transactions.execute(status -> enrollInTransaction(code, request));
    }

    private Enrollment enrollInTransaction(String code, EnrollmentRequest request) {
        String codeHash = sha256Hex(code);
        InviteRow invite = jdbc.sql("""
            select id, allowed_projects_json::text as projects_json, expires_at, used_at
            from node_invite
            where code_hash = :codeHash
            for update
            """)
            .param("codeHash", codeHash)
            .query((rs, rowNum) -> new InviteRow(
                rs.getObject("id", UUID.class),
                rs.getString("projects_json"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("used_at") == null
                    ? null
                    : rs.getTimestamp("used_at").toInstant()))
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("invite not found"));

        if (invite.usedAt() != null) {
            throw new InviteAlreadyUsedException("invite already used");
        }
        if (!invite.expiresAt().isAfter(clock.instant())) {
            throw new InviteExpiredException("invite expired");
        }

        UUID nodeId = UUID.randomUUID();
        DeviceCertificateAuthority.SignedDeviceCertificate signed =
            certificateAuthority.signDeviceCsr(
                new DeviceCertificateAuthority.UUIDSubject(nodeId), request.csrPem());

        String publicKeySha256 = sha256Hex(signed.publicKey().getEncoded());
        String runnerTag = "repair-node-" + nodeId;
        String allowedProjectsJson = invite.projectsJson();
        Set<String> allowedProjects = readProjects(allowedProjectsJson);

        jdbc.sql("""
            insert into repair_node(
              id, name, owner_id, public_key_sha256, certificate_serial, runner_tag,
              state, desired_config_json, concurrency_limit, allowed_projects_json,
              capabilities_json)
            values (
              :id, :name, :ownerId, :publicKeySha, :serial, :runnerTag,
              :state, cast(:desiredConfig as jsonb), :concurrency,
              cast(:allowedProjects as jsonb), cast(:capabilities as jsonb))
            """)
            .param("id", nodeId)
            .param("name", request.name())
            .param("ownerId", "pending")
            .param("publicKeySha", publicKeySha256)
            .param("serial", signed.serialHex())
            .param("runnerTag", runnerTag)
            .param("state", PENDING_CONFIRMATION)
            .param("desiredConfig", "{\"concurrency\":" + DEFAULT_CONCURRENCY + "}")
            .param("concurrency", DEFAULT_CONCURRENCY)
            .param("allowedProjects", allowedProjectsJson)
            .param("capabilities", "{}")
            .update();

        Instant usedAt = clock.instant();
        jdbc.sql("""
            update node_invite
            set used_at = :usedAt, used_by_node = :nodeId
            where id = :id
            """)
            .param("usedAt", java.sql.Timestamp.from(usedAt))
            .param("nodeId", nodeId)
            .param("id", invite.id())
            .update();

        String requestId = UUID.randomUUID().toString();
        jdbc.sql("""
            insert into audit_log(
              actor_type, actor_id, action, object_type, object_id, request_id, detail_json)
            values (
              'NODE', :actorId, 'NODE_ENROLLED', 'REPAIR_NODE', :objectId, :requestId,
              cast(:detail as jsonb))
            """)
            .param("actorId", request.name())
            .param("objectId", nodeId.toString())
            .param("requestId", requestId)
            .param("detail", "{\"inviteId\":\"" + invite.id() + "\"}")
            .update();

        return new Enrollment(
            nodeId,
            signed.certificatePem(),
            signed.caPem(),
            signed.expiresAt(),
            runnerTag,
            allowedProjects);
    }

    private Set<String> readProjects(String projectsJson) {
        var node = jsonMapper.readTree(projectsJson);
        Set<String> projects = new LinkedHashSet<>();
        if (node.isArray()) {
            node.forEach(item -> projects.add(item.asString()));
        }
        return Set.copyOf(projects);
    }

    private static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    public record EnrollmentRequest(String name, String csrPem) {}

    public record Enrollment(
        UUID nodeId,
        String certificatePem,
        String caPem,
        Instant expiresAt,
        String runnerTag,
        Set<String> allowedProjects) {}

    public record EnrollmentResponse(
        UUID nodeId,
        String certificatePem,
        String caPem,
        Instant expiresAt,
        String runnerTag,
        Set<String> allowedProjects) {
        static EnrollmentResponse from(Enrollment enrollment) {
            return new EnrollmentResponse(
                enrollment.nodeId(),
                enrollment.certificatePem(),
                enrollment.caPem(),
                enrollment.expiresAt(),
                enrollment.runnerTag(),
                enrollment.allowedProjects());
        }
    }

    private record InviteRow(UUID id, String projectsJson, Instant expiresAt, Instant usedAt) {}
}

final class InviteAlreadyUsedException extends RuntimeException {
    InviteAlreadyUsedException(String message) {
        super(message);
    }
}

final class InviteExpiredException extends RuntimeException {
    InviteExpiredException(String message) {
        super(message);
    }
}
