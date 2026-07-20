package com.company.loopengine.execution;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.loopengine.node.application.NodeHeartbeatService;
import com.company.loopengine.node.application.NodeHeartbeatService.ConfirmRequest;
import com.company.loopengine.node.enrollment.DeviceCertificateAuthority;
import com.company.loopengine.node.security.DeviceCertificateFilter;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AttemptApiTest {
    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long PIPELINE_ID = 101L;
    private static final long JOB_ID = 202L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final WireMockServer GITLAB = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    private Clock clock;
    private DeviceCertificateAuthority ca;
    private NodeHeartbeatService nodes;
    private UUID taskId;
    private UUID nodeId;
    private UUID otherNodeId;
    private UUID reservationId;
    private UUID attachmentId;
    private X509Certificate nodeCert;
    private X509Certificate otherNodeCert;

    @BeforeAll
    static void startGitlab() {
        GITLAB.start();
    }

    @AfterAll
    static void stopGitlab() {
        GITLAB.stop();
    }

    @DynamicPropertySource
    static void gitlabProperties(DynamicPropertyRegistry registry) {
        registry.add("loop.gitlab.api-url", GITLAB::baseUrl);
        registry.add("loop.gitlab.token", () -> "glpat-test");
    }

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.now().minusSeconds(60), ZoneOffset.UTC);
        ca = DeviceCertificateAuthority.ephemeralForTests(clock);
        nodes = new NodeHeartbeatService(jdbc, clock, transactionManager, jsonMapper);
        cleanup();
        NodeFixture nodeA = enrollNode("node-a");
        NodeFixture nodeB = enrollNode("node-b");
        nodeId = nodeA.nodeId();
        otherNodeId = nodeB.nodeId();
        nodeCert = nodeA.certificate();
        otherNodeCert = nodeB.certificate();
        taskId = insertReservedTask();
        Instant now = Instant.now();
        reservationId = insertActiveReservation(taskId, nodeId, PIPELINE_ID, now, now.plusSeconds(120));
        attachmentId = insertAttachment(taskId);
        stubAttachment();
    }

    @Test
    void startsOnlyTheReservedNodeAndAcknowledgesDuplicateEvents() throws Exception {
        BootstrapResponse bootstrap = bootstrap(nodeCert, taskId, reservationId, PIPELINE_ID, JOB_ID);
        assertThat(bootstrap.taskToken()).isNotBlank();
        assertThat(bootstrap.taskPackage().path("attemptId").asString()).isEqualTo(bootstrap.attemptId());

        assertThat(append(bootstrap.taskToken(), bootstrap.attemptId(), events(bootstrap, 1, 2, 3)))
            .isEqualTo(3);
        assertThat(append(bootstrap.taskToken(), bootstrap.attemptId(), events(bootstrap, 2, 3, 4)))
            .isEqualTo(4);
        assertThat(eventCount(bootstrap.attemptId())).isEqualTo(4);
        assertThat(taskState(taskId)).isEqualTo("RUNNING");
        assertThat(reservationState(reservationId)).isEqualTo("CONSUMED");
    }

    @Test
    void rejectsWrongNode() throws Exception {
        mvc.perform(post("/api/node/v1/attempts/bootstrap")
                .with(deviceCert(otherNodeCert))
                .contentType(APPLICATION_JSON)
                .content(bootstrapJson(taskId, reservationId, PIPELINE_ID, JOB_ID)))
            .andExpect(status().isForbidden());
    }

    @Test
    void rejectsExpiredReservation() throws Exception {
        jdbc.sql("update task_reservation set expires_at = :expires where id = :id")
            .param("expires", Timestamp.from(Instant.now().minusSeconds(1)))
            .param("id", reservationId)
            .update();

        mvc.perform(post("/api/node/v1/attempts/bootstrap")
                .with(deviceCert(nodeCert))
                .contentType(APPLICATION_JSON)
                .content(bootstrapJson(taskId, reservationId, PIPELINE_ID, JOB_ID)))
            .andExpect(status().isConflict());
    }

    @Test
    void rejectsWrongTaskToken() throws Exception {
        BootstrapResponse bootstrap = bootstrap(nodeCert, taskId, reservationId, PIPELINE_ID, JOB_ID);

        mvc.perform(post("/api/node/v1/attempts/" + bootstrap.attemptId() + "/events:batch")
                .with(deviceCert(nodeCert))
                .header("Authorization", "Bearer wrong-token")
                .contentType(APPLICATION_JSON)
                .content(eventsJson(bootstrap, 1)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsSequenceGap() throws Exception {
        BootstrapResponse bootstrap = bootstrap(nodeCert, taskId, reservationId, PIPELINE_ID, JOB_ID);
        append(bootstrap.taskToken(), bootstrap.attemptId(), events(bootstrap, 1));

        mvc.perform(post("/api/node/v1/attempts/" + bootstrap.attemptId() + "/events:batch")
                .with(deviceCert(nodeCert))
                .header("Authorization", "Bearer " + bootstrap.taskToken())
                .contentType(APPLICATION_JSON)
                .content(eventsJson(bootstrap, 3)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("gap")));
    }

    @Test
    void proxiesAttachmentWithoutExposingGitlabUrl() throws Exception {
        BootstrapResponse bootstrap = bootstrap(nodeCert, taskId, reservationId, PIPELINE_ID, JOB_ID);

        mvc.perform(get("/api/node/v1/attempts/" + bootstrap.attemptId() + "/attachments/" + attachmentId)
                .with(deviceCert(nodeCert))
                .header("Authorization", "Bearer " + bootstrap.taskToken()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(header().doesNotExist("Location"));

        String body = mvc.perform(get("/api/node/v1/attempts/" + bootstrap.attemptId() + "/attachments/" + attachmentId)
                .with(deviceCert(nodeCert))
                .header("Authorization", "Bearer " + bootstrap.taskToken()))
            .andReturn()
            .getResponse()
            .getContentAsString();
        assertThat(body).doesNotContain("gitlab.example");
        assertThat(body).doesNotContain("glpat");
    }

    @Test
    void rejectsAttachmentDigestMismatch() throws Exception {
        BootstrapResponse bootstrap = bootstrap(nodeCert, taskId, reservationId, PIPELINE_ID, JOB_ID);
        GITLAB.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/uploads/evidence.png"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "image/png")
                .withBody("changed-bytes")));

        mvc.perform(get("/api/node/v1/attempts/" + bootstrap.attemptId() + "/attachments/" + attachmentId)
                .with(deviceCert(nodeCert))
                .header("Authorization", "Bearer " + bootstrap.taskToken()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ATTACHMENT_CHANGED")));
    }

    private BootstrapResponse bootstrap(
            X509Certificate cert, UUID task, UUID reservation, long pipelineId, long jobId) throws Exception {
        MvcResult result = mvc.perform(post("/api/node/v1/attempts/bootstrap")
                .with(deviceCert(cert))
                .contentType(APPLICATION_JSON)
                .content(bootstrapJson(task, reservation, pipelineId, jobId)))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = jsonMapper.readTree(result.getResponse().getContentAsString());
        return new BootstrapResponse(
            body.path("attemptId").asString(),
            body.path("taskToken").asString(),
            body.path("taskPackage"));
    }

    private long append(String taskToken, String attemptId, List<JsonNode> batch) throws Exception {
        MvcResult result = mvc.perform(post("/api/node/v1/attempts/" + attemptId + "/events:batch")
                .with(deviceCert(nodeCert))
                .header("Authorization", "Bearer " + taskToken)
                .contentType(APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("events", batch))))
            .andExpect(status().isOk())
            .andReturn();
        return jsonMapper.readTree(result.getResponse().getContentAsString()).path("ackSeq").asLong();
    }

    private List<JsonNode> events(BootstrapResponse bootstrap, int... seqs) throws Exception {
        JsonNode root = jsonMapper.readTree(eventsJson(bootstrap, seqs));
        java.util.ArrayList<JsonNode> batch = new java.util.ArrayList<>();
        root.path("events").forEach(batch::add);
        return batch;
    }

    private String eventsJson(BootstrapResponse bootstrap, int... seqs) throws Exception {
        StringBuilder builder = new StringBuilder("{\"events\":[");
        for (int i = 0; i < seqs.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append("""
                {"taskId":"%s","attemptId":"%s","nodeId":"%s","seq":%d,
                 "time":"2026-07-20T12:01:00Z","type":"agent.started","payload":{"step":%d}}
                """.formatted(bootstrap.taskPackage().path("taskId").asString(),
                bootstrap.attemptId(),
                bootstrap.taskPackage().path("nodeId").asString(),
                seqs[i],
                seqs[i]));
        }
        builder.append("]}");
        return builder.toString();
    }

    private static String bootstrapJson(UUID task, UUID reservation, long pipelineId, long jobId) {
        return """
            {"taskId":"%s","reservationId":"%s","pipelineId":%d,"jobId":%d}
            """.formatted(task, reservation, pipelineId, jobId);
    }

    private RequestPostProcessor deviceCert(X509Certificate certificate) {
        return request -> {
            request.setAttribute(
                DeviceCertificateFilter.SERVLET_CERT_ATTR, new X509Certificate[] {certificate});
            return request;
        };
    }

    private NodeFixture enrollNode(String name) {
        UUID id = UUID.randomUUID();
        var signed = ca.signDeviceCsr(new DeviceCertificateAuthority.UUIDSubject(id), ed25519Csr());
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
            .param("id", id)
            .param("name", name)
            .param("publicKeySha", publicKeySha)
            .param("serial", signed.serialHex())
            .param("runnerTag", "repair-node-" + id)
            .param("projects", projectsJson)
            .update();
        nodes.confirm(id, new ConfirmRequest(Set.of("backend-a"), Map.of("os", "linux")));
        return new NodeFixture(id, certificate(signed.certificatePem()));
    }

    private UUID insertReservedTask() {
        UUID defectId = UUID.randomUUID();
        long iid = Math.abs(defectId.getLeastSignificantBits() % 1_000_000);
        jdbc.sql("""
            insert into defect(
              id, intake_project_id, issue_iid, issue_global_id, issue_url,
              title, description, state, source_updated_at, version)
            values (
              :id, 1, :iid, :iid, :url,
              'title', 'desc', 'QUEUED', :now, 0)
            """)
            .param("id", defectId)
            .param("iid", iid)
            .param("url", "https://gitlab.example/group/defect-intake/-/issues/" + iid)
            .param("now", Timestamp.from(T0))
            .update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            insert into repair_task(
              id, defect_id, defect_revision, project_key, profile_revision,
              profile_snapshot_json, base_sha, state, priority, created_at)
            values (
              :id, :defectId, 1, 'backend-a', 1,
              cast(:profile as jsonb), 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
              'RESERVED', 10, :createdAt)
            """)
            .param("id", id)
            .param("defectId", defectId)
            .param("profile", profileSnapshot())
            .param("createdAt", Timestamp.from(T0))
            .update();
        return id;
    }

    private UUID insertAttachment(UUID task) {
        UUID defectId = jdbc.sql("select defect_id from repair_task where id = :id")
            .param("id", task)
            .query(UUID.class)
            .single();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            insert into defect_attachment(
              id, defect_id, source_url, name, content_type, size_bytes, sha256, source_updated_at)
            values (
              :id, :defectId, :url, 'evidence.png', 'image/png', 5,
              '277089d91c0bdf4f2e6862ba7e4a07605119431f5d13f726dd352b06f1b206a9', :now)
            """)
            .param("id", id)
            .param("defectId", defectId)
            .param("url", GITLAB.baseUrl() + "/uploads/evidence.png")
            .param("now", Timestamp.from(T0))
            .update();
        return id;
    }

    private void stubAttachment() {
        GITLAB.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/uploads/evidence.png"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "image/png")
                .withBody("bytes")));
    }

    private static String profileSnapshot() {
        return """
            {
              "repository":"group/backend-a",
              "defaultBranch":"main",
              "modules":["services/order"],
              "validationCommands":[
                {"program":"mvn","args":["-q","test"],"timeoutSeconds":600,"required":true}
              ]
            }
            """;
    }

    private UUID insertActiveReservation(
            UUID task, UUID node, long pipelineId, Instant createdAt, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            insert into task_reservation(id, task_id, node_id, pipeline_id, expires_at, state, created_at)
            values (:id, :taskId, :nodeId, :pipelineId, :expiresAt, 'ACTIVE', :createdAt)
            """)
            .param("id", id)
            .param("taskId", task)
            .param("nodeId", node)
            .param("pipelineId", pipelineId)
            .param("expiresAt", Timestamp.from(expiresAt))
            .param("createdAt", Timestamp.from(createdAt))
            .update();
        return id;
    }

    private long eventCount(String attemptId) {
        return jdbc.sql("select count(*) from task_event where attempt_id = :id")
            .param("id", UUID.fromString(attemptId))
            .query(Long.class)
            .single();
    }

    private String taskState(UUID id) {
        return jdbc.sql("select state from repair_task where id = :id")
            .param("id", id)
            .query(String.class)
            .single();
    }

    private String reservationState(UUID id) {
        return jdbc.sql("select state from task_reservation where id = :id")
            .param("id", id)
            .query(String.class)
            .single();
    }

    private void cleanup() {
        jdbc.sql("delete from task_event").update();
        jdbc.sql("delete from repair_attempt").update();
        jdbc.sql("delete from task_reservation").update();
        jdbc.sql("delete from defect_attachment").update();
        jdbc.sql("delete from repair_task").update();
        jdbc.sql("delete from defect").update();
        jdbc.sql("delete from node_heartbeat").update();
        jdbc.sql("delete from audit_log").update();
        jdbc.sql("delete from repair_node").update();
        jdbc.sql("delete from node_invite").update();
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

    private record BootstrapResponse(String attemptId, String taskToken, JsonNode taskPackage) {}

    private record NodeFixture(UUID nodeId, X509Certificate certificate) {}
}
