package com.company.loopengine.node.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.loopengine.node.application.NodeHeartbeatService;
import com.company.loopengine.node.application.NodeHeartbeatService.ConfirmRequest;
import com.company.loopengine.node.enrollment.DeviceCertificateAuthority;
import jakarta.servlet.ServletException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest
class DeviceCertificateFilterTest {
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

    private Clock clock;
    private DeviceCertificateAuthority ca;
    private NodeHeartbeatService nodes;
    private DeviceCertificateFilter filter;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        ca = DeviceCertificateAuthority.ephemeralForTests(clock);
        nodes = new NodeHeartbeatService(jdbc, clock, transactionManager, jsonMapper);
        filter = new DeviceCertificateFilter(nodes);
        jdbc.sql("delete from node_heartbeat").update();
        jdbc.sql("delete from audit_log").update();
        jdbc.sql("delete from repair_node").update();
        jdbc.sql("delete from node_invite").update();
    }

    @Test
    void certificateForNodeACannotUpdateNodeB() throws ServletException, IOException {
        NodeFixture nodeA = enrollConfirmed("node-a");
        NodeFixture nodeB = enrollConfirmed("node-b");

        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST", "/api/node/v1/nodes/" + nodeB.nodeId() + "/heartbeat");
        request.setAttribute(
            DeviceCertificateFilter.SERVLET_CERT_ATTR, new X509Certificate[] {nodeA.certificate()});
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(request.getAttribute(DeviceCertificateFilter.ATTR_NODE_ID)).isNull();
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void matchingCertificateAllowsNodePath() throws ServletException, IOException {
        NodeFixture node = enrollConfirmed("node-ok");

        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST", "/api/node/v1/nodes/" + node.nodeId() + "/heartbeat");
        request.setAttribute(
            DeviceCertificateFilter.SERVLET_CERT_ATTR, new X509Certificate[] {node.certificate()});
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(DeviceCertificateFilter.ATTR_NODE_ID))
            .isEqualTo(node.nodeId());
        assertThat(chain.getRequest()).isSameAs(request);
    }

    private NodeFixture enrollConfirmed(String name) {
        UUID nodeId = UUID.randomUUID();
        var signed = ca.signDeviceCsr(
            new DeviceCertificateAuthority.UUIDSubject(nodeId), ed25519Csr());
        String publicKeySha = sha256Hex(signed.publicKey().getEncoded());
        String projectsJson = jsonMapper.writeValueAsString(List.of("backend-a"));
        jdbc.sql("""
            insert into repair_node(
              id, name, owner_id, public_key_sha256, certificate_serial, runner_tag,
              state, desired_config_json, concurrency_limit, allowed_projects_json, capabilities_json)
            values (
              :id, :name, 'pending', :publicKeySha, :serial, :runnerTag,
              'PENDING_CONFIRMATION', cast('{"concurrency":2}' as jsonb), 2,
              cast(:projects as jsonb), cast('{}' as jsonb))
            """)
            .param("id", nodeId)
            .param("name", name)
            .param("publicKeySha", publicKeySha)
            .param("serial", signed.serialHex())
            .param("runnerTag", "repair-node-" + nodeId)
            .param("projects", projectsJson)
            .update();
        nodes.confirm(nodeId, new ConfirmRequest(Set.of("backend-a"), Map.of("os", "linux")));
        return new NodeFixture(nodeId, certificate(signed.certificatePem()));
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

    private static X509Certificate certificate(String pem) {
        try {
            String normalized = pem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(normalized);
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
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

    private record NodeFixture(UUID nodeId, X509Certificate certificate) {}
}
