package com.company.loopengine.node.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.loopengine.node.application.NodeHeartbeatService.ConfirmRequest;
import com.company.loopengine.node.application.NodeHeartbeatService.HeartbeatRequest;
import com.company.loopengine.node.application.NodeHeartbeatService.HeartbeatResponse;
import com.company.loopengine.node.application.NodeHeartbeatService.NodeConfig;
import com.company.loopengine.node.enrollment.DeviceCertificateAuthority;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest
class NodeHeartbeatServiceTest {
    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant FIXED_NOW = Instant.parse("2026-07-19T12:00:00Z");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JsonMapper jsonMapper;

    private AtomicReference<Instant> now;
    private Clock clock;
    private DeviceCertificateAuthority ca;
    private NodeHeartbeatService nodes;
    private NodeHeartbeatService service;

    @BeforeEach
    void setUp() {
        now = new AtomicReference<>(FIXED_NOW);
        clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        ca = DeviceCertificateAuthority.ephemeralForTests(clock);
        service = nodes = new NodeHeartbeatService(jdbc, clock, transactionManager, jsonMapper);
        jdbc.sql("delete from node_heartbeat").update();
        jdbc.sql("delete from audit_log").update();
        jdbc.sql("delete from repair_node").update();
        jdbc.sql("delete from node_invite").update();
    }

    @Test
    void heartbeatReturnsDesiredConfigUntilTheNodeAppliesIt() {
        UUID node = enrolledAndConfirmedNode(2);
        nodes.setDesired(node, new NodeConfig(4, Set.of("backend-a"), false));

        HeartbeatResponse first = service.heartbeat(node, heartbeat(2, 0));
        assertThat(first.desiredRevision()).isEqualTo(2);
        assertThat(first.config().concurrency()).isEqualTo(4);

        HeartbeatResponse second = service.heartbeat(node, heartbeat(2, 2));
        assertThat(second.config()).isNull();
        assertThat(nodes.get(node).appliedRevision()).isEqualTo(2);
    }

    @Test
    void fortySixSecondsWithoutHeartbeatBecomesOffline() {
        UUID node = enrolledAndConfirmedNode(2);
        // Health sweeping only applies after leaving the enrollment/provision pipeline.
        promoteOutOfProvisionPipeline(node);
        service.heartbeat(node, heartbeat(2, 0));
        assertThat(nodes.get(node).state()).isEqualTo("ONLINE");

        now.set(FIXED_NOW.plus(Duration.ofSeconds(46)));
        service.sweepNodeStates();
        assertThat(nodes.get(node).state()).isEqualTo("OFFLINE");
    }

    @Test
    void sweepDoesNotMovePendingRunnerToOfflineWithoutHeartbeat() {
        UUID node = enrolledAndConfirmedNode(2);
        assertThat(nodes.get(node).state()).isEqualTo("PENDING_RUNNER");

        now.set(FIXED_NOW.plus(Duration.ofSeconds(46)));
        service.sweepNodeStates();

        assertThat(nodes.get(node).state()).isEqualTo("PENDING_RUNNER");
    }

    @Test
    void heartbeatKeepsPendingRunnerUntilProvisioned() {
        UUID node = enrolledAndConfirmedNode(2);
        service.heartbeat(node, heartbeat(2, 0));
        assertThat(nodes.get(node).state()).isEqualTo("PENDING_RUNNER");
        assertThat(nodes.get(node).lastHeartbeatAt()).isEqualTo(FIXED_NOW);
    }

    private UUID enrolledAndConfirmedNode(int concurrency) {
        UUID nodeId = insertPendingNode("dev-laptop", Set.of("backend-a"), concurrency);
        service.confirm(
            nodeId,
            new ConfirmRequest(Set.of("backend-a"), Map.of("os", "linux", "arch", "amd64")));
        return nodeId;
    }

    private void promoteOutOfProvisionPipeline(UUID nodeId) {
        jdbc.sql("""
            update repair_node
            set state = 'ONLINE', updated_at = :updatedAt
            where id = :id
            """)
            .param("updatedAt", java.sql.Timestamp.from(clock.instant()))
            .param("id", nodeId)
            .update();
    }

    private UUID insertPendingNode(String name, Set<String> projects, int concurrency) {
        UUID nodeId = UUID.randomUUID();
        var signed = ca.signDeviceCsr(
            new DeviceCertificateAuthority.UUIDSubject(nodeId), ed25519Csr());
        String publicKeySha = sha256Hex(signed.publicKey().getEncoded());
        String projectsJson = jsonMapper.writeValueAsString(List.copyOf(projects));
        String desired = "{\"concurrency\":" + concurrency + ",\"allowedProjects\":"
            + projectsJson + ",\"drain\":false}";
        jdbc.sql("""
            insert into repair_node(
              id, name, owner_id, public_key_sha256, certificate_serial, runner_tag,
              state, desired_revision, applied_revision, desired_config_json, concurrency_limit,
              allowed_projects_json, capabilities_json)
            values (
              :id, :name, 'pending', :publicKeySha, :serial, :runnerTag,
              'PENDING_CONFIRMATION', 1, 0, cast(:desired as jsonb), :concurrency,
              cast(:projects as jsonb), cast('{}' as jsonb))
            """)
            .param("id", nodeId)
            .param("name", name)
            .param("publicKeySha", publicKeySha)
            .param("serial", signed.serialHex())
            .param("runnerTag", "repair-node-" + nodeId)
            .param("desired", desired)
            .param("concurrency", concurrency)
            .param("projects", projectsJson)
            .update();
        return nodeId;
    }

    private HeartbeatRequest heartbeat(int slotLimit, int appliedRevision) {
        return new HeartbeatRequest(
            clock.instant(),
            appliedRevision,
            new HeartbeatRequest.Runner("online", "18.2.0"),
            new HeartbeatRequest.Slots(0, slotLimit),
            new HeartbeatRequest.Resources(26.1, 8_589_934_592L, 53_687_091_200L),
            new HeartbeatRequest.Tools(
                "linux", "amd64", "21.0.8", "3.9.11", "24.18.0", "10.14.0", "1.0.180"),
            List.of(),
            null);
    }

    private static String ed25519Csr() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519", "BC");
            KeyPair keyPair = generator.generateKeyPair();
            X500Name subject = new X500Name("CN=repair-node-pending");
            var csr = new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic())
                .build(new JcaContentSignerBuilder("Ed25519").setProvider("BC").build(keyPair.getPrivate()));
            String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(csr.getEncoded());
            return "-----BEGIN CERTIFICATE REQUEST-----\n" + body + "\n-----END CERTIFICATE REQUEST-----\n";
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
