package com.company.loopengine.node.enrollment;

import static java.time.temporal.ChronoUnit.DAYS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.loopengine.node.enrollment.NodeEnrollmentService.Enrollment;
import com.company.loopengine.node.enrollment.NodeEnrollmentService.EnrollmentRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
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
class NodeEnrollmentServiceTest {
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
    private NodeEnrollmentService invites;
    private NodeEnrollmentService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        ca = DeviceCertificateAuthority.ephemeralForTests(clock);
        service = invites = new NodeEnrollmentService(
            jdbc, ca, clock, transactionManager, jsonMapper);
        jdbc.sql("delete from audit_log").update();
        jdbc.sql("delete from repair_node").update();
        jdbc.sql("delete from node_invite").update();
    }

    @Test
    void consumesInviteAndSignsTheSubmittedDeviceKeyOnce() {
        String code = invites.create(Set.of("backend-a"), clock.instant().plusSeconds(600), "admin");
        Enrollment first = service.enroll(code, request("dev-laptop", ed25519Csr()));
        assertThat(first.runnerTag()).isEqualTo("repair-node-" + first.nodeId());
        assertThat(certificate(first.certificatePem()).getNotAfter().toInstant())
            .isEqualTo(clock.instant().plus(90, DAYS));
        assertThatThrownBy(() -> service.enroll(code, request("copy", ed25519Csr())))
            .isInstanceOf(InviteAlreadyUsedException.class);
    }

    @Test
    void rejectsExpiredInvite() {
        String code = invites.create(Set.of("backend-a"), clock.instant().minusSeconds(1), "admin");
        assertThatThrownBy(() -> service.enroll(code, request("late", ed25519Csr())))
            .isInstanceOf(InviteExpiredException.class);
    }

    @Test
    void failedCertificateSigningLeavesTheInviteUnused() {
        String code = invites.create(Set.of("backend-a"), clock.instant().plusSeconds(600), "admin");
        NodeEnrollmentService failing = new NodeEnrollmentService(
            jdbc, DeviceCertificateAuthority.failingForTests(), clock, transactionManager, jsonMapper);

        assertThatThrownBy(() -> failing.enroll(code, request("dev-laptop", ed25519Csr())))
            .isInstanceOf(CertificateSigningException.class);

        Enrollment recovered = service.enroll(code, request("dev-laptop", ed25519Csr()));
        assertThat(recovered.nodeId()).isNotNull();
        assertThat(jdbc.sql("select count(*) from node_invite where used_at is not null")
            .query(Long.class).single()).isEqualTo(1L);
    }

    private static EnrollmentRequest request(String name, String csrPem) {
        return new EnrollmentRequest(name, csrPem);
    }

    private static String ed25519Csr() {
        try {
            KeyPair keyPair = generateEd25519();
            X500Name subject = new X500Name("CN=repair-node-pending");
            var csr = new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic())
                .build(new JcaContentSignerBuilder("Ed25519").setProvider("BC").build(keyPair.getPrivate()));
            return pem("CERTIFICATE REQUEST", csr.getEncoded());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static KeyPair generateEd25519() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519", "BC");
        return generator.generateKeyPair();
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
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
}
