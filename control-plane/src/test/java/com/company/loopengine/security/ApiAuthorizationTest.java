package com.company.loopengine.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.loopengine.node.application.NodeHeartbeatService;
import com.company.loopengine.node.application.NodeHeartbeatService.ConfirmRequest;
import com.company.loopengine.node.enrollment.DeviceCertificateAuthority;
import com.company.loopengine.node.security.DeviceCertificateFilter;
import java.io.ByteArrayInputStream;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiAuthorizationTest.FixtureControllers.class)
@TestPropertySource(
        properties = {
            "loop.security.enabled=true",
            "spring.autoconfigure.exclude=",
            "spring.security.oauth2.client.registration.gitlab.client-id=test-client",
            "spring.security.oauth2.client.registration.gitlab.client-secret=test-secret",
            "spring.security.oauth2.client.registration.gitlab.authorization-grant-type=authorization_code",
            "spring.security.oauth2.client.registration.gitlab.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
            "spring.security.oauth2.client.registration.gitlab.scope=read_user,openid,profile,email",
            "spring.security.oauth2.client.registration.gitlab.provider=gitlab",
            "spring.security.oauth2.client.provider.gitlab.authorization-uri=http://gitlab.test/oauth/authorize",
            "spring.security.oauth2.client.provider.gitlab.token-uri=http://gitlab.test/oauth/token",
            "spring.security.oauth2.client.provider.gitlab.user-info-uri=http://gitlab.test/api/v4/user",
            "spring.security.oauth2.client.provider.gitlab.user-name-attribute=id",
            "loop.security.gitlab.admin-group=loop-admins",
            "loop.gitlab.url=http://gitlab.test"
        })
class ApiAuthorizationTest {
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
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JsonMapper jsonMapper;

    @ParameterizedTest
    @CsvSource({
        "ADMIN,/api/admin/node-invites,POST,201",
        "PROJECT_OWNER,/api/projects/backend-a/revisions,POST,201",
        "OBSERVER,/api/projects/backend-a/revisions,POST,403",
        "NODE_OWNER,/api/nodes/node-owned/drain,POST,202",
        "NODE_OWNER,/api/nodes/node-other/drain,POST,403"
    })
    void enforcesRoleAndObjectOwnership(String role, String path, String method, int status)
            throws Exception {
        seedOwnership();
        performWithUser(role, path, method).andExpect(status().is(status));
    }

    @Test
    void unauthenticatedApiReturns401() throws Exception {
        mvc.perform(get("/api/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void validCsrfWriteSucceeds() throws Exception {
        seedOwnership();
        mvc.perform(post("/api/admin/node-invites")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());
    }

    @Test
    void nodeJoinDoesNotRequireOAuthSession() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/nodes/join")
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {"code":"missing-invite","name":"dev-laptop","csrPem":"not-a-csr"}
                            """))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("invite join must not be gated by browser OAuth")
                .isNotIn(401, 302);
    }

    @Test
    void deviceCertificateAllowsNodeHeartbeatWithoutOAuth() throws Exception {
        NodeFixture node = enrollConfirmedNode("sec-heartbeat");
        MvcResult result = mvc.perform(post("/api/node/v1/nodes/" + node.nodeId() + "/heartbeat")
                        .with(deviceCert(node.certificate()))
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {
                              "observedAt":"2026-07-19T12:00:00Z",
                              "appliedRevision":0,
                              "runner":{"status":"online","version":"18.2.0"},
                              "slots":{"active":0,"limit":2},
                              "resources":{
                                "cpuPercent":26.1,
                                "memoryAvailableBytes":8589934592,
                                "diskAvailableBytes":53687091200
                              },
                              "tools":{
                                "os":"linux","arch":"amd64","java":"21.0.8","maven":"3.9.11",
                                "node":"24.18.0","pnpm":"10.14.0","opencode":"1.0.180"
                              },
                              "activeAttemptIds":[],
                              "lastError":null
                            }
                            """))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("device-authenticated node APIs must not require SecurityContext login")
                .isNotIn(401, 302);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void deviceCertificateAllowsAttemptBootstrapWithoutOAuth() throws Exception {
        NodeFixture node = enrollConfirmedNode("sec-bootstrap");
        MvcResult result = mvc.perform(post("/api/node/v1/attempts/bootstrap")
                        .with(deviceCert(node.certificate()))
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {
                              "taskId":"33333333-3333-3333-3333-333333333333",
                              "reservationId":"44444444-4444-4444-4444-444444444444",
                              "pipelineId":1,
                              "jobId":1
                            }
                            """))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("bootstrap must pass security when device certificate is present")
                .isNotIn(401, 302);
    }

    private ResultActions performWithUser(String role, String path, String method) throws Exception {
        MockHttpServletRequestBuilder request =
                switch (method) {
                    case "POST" -> post(path).contentType(APPLICATION_JSON).content(bodyFor(path));
                    case "GET" -> get(path);
                    default -> throw new IllegalArgumentException(method);
                };
        String username =
                switch (role) {
                    case "PROJECT_OWNER" -> "1001";
                    case "NODE_OWNER" -> "user-NODE_OWNER";
                    case "ADMIN" -> "admin";
                    case "OBSERVER" -> "observer";
                    default -> "user-" + role;
                };
        return mvc.perform(request.with(user(username).roles(role)).with(csrf()));
    }

    private static String bodyFor(String path) {
        if (path.contains("/revisions")) {
            return """
                {
                  "repository": "group/backend-a",
                  "defaultBranch": "main",
                  "modules": ["services/order"],
                  "contextPaths": ["README.md"],
                  "validationCommands": [
                    {"program": "mvn", "args": ["-B", "test"], "timeoutSeconds": 1200}
                  ],
                  "allowedOs": ["linux"],
                  "allowedNodeIds": [],
                  "allowedNodeOwnerIds": ["backend-team"],
                  "requiredTools": {"java": ">=21"},
                  "forbiddenPaths": [".git/**"],
                  "maxChangedFiles": 40,
                  "maxPatchBytes": 1048576,
                  "maxRepairRounds": 2,
                  "maxExternalAttempts": 2,
                  "retryFunctionalFailure": false,
                  "targetBranch": "main",
                  "branchPrefix": "repair/",
                  "reviewers": ["backend-maintainers"]
                }
                """;
        }
        return "{}";
    }

    private void seedOwnership() {
        jdbc.sql(
                        """
            insert into project_profile (id, project_key, gitlab_path)
            values ('11111111-1111-1111-1111-111111111111', 'backend-a', 'group/backend-a')
            on conflict (project_key) do nothing
            """)
                .update();
        jdbc.sql(
                        """
            insert into project_profile_owner (profile_id, gitlab_user_id, added_by)
            values ('11111111-1111-1111-1111-111111111111', 1001, 'seed')
            on conflict do nothing
            """)
                .update();
        jdbc.sql(
                        """
            insert into repair_node (
              id, name, owner_id, public_key_sha256, certificate_serial, runner_tag, state,
              desired_config_json, concurrency_limit, allowed_projects_json, capabilities_json)
            values
              ('22222222-2222-2222-2222-222222222201', 'node-owned', 'user-NODE_OWNER',
               repeat('a', 64), 'serial-owned', 'tag-owned', 'ONLINE',
               '{"concurrency":1,"allowedProjects":[],"drain":false}'::jsonb, 1, '[]'::jsonb, '{}'::jsonb),
              ('22222222-2222-2222-2222-222222222202', 'node-other', 'someone-else',
               repeat('b', 64), 'serial-other', 'tag-other', 'ONLINE',
               '{"concurrency":1,"allowedProjects":[],"drain":false}'::jsonb, 1, '[]'::jsonb, '{}'::jsonb)
            on conflict (id) do nothing
            """)
                .update();
    }

    private NodeFixture enrollConfirmedNode(String name) {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        DeviceCertificateAuthority ca = DeviceCertificateAuthority.ephemeralForTests(clock);
        NodeHeartbeatService nodes =
                new NodeHeartbeatService(jdbc, clock, transactionManager, jsonMapper);
        UUID nodeId = UUID.randomUUID();
        var signed = ca.signDeviceCsr(new DeviceCertificateAuthority.UUIDSubject(nodeId), ed25519Csr());
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

    private static RequestPostProcessor deviceCert(X509Certificate certificate) {
        return request -> {
            request.setAttribute(
                    DeviceCertificateFilter.SERVLET_CERT_ATTR, new X509Certificate[] {certificate});
            return request;
        };
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

    @TestConfiguration
    static class FixtureControllers {
        @Bean
        AdminInviteController adminInviteController() {
            return new AdminInviteController();
        }

        @Bean
        NodeDrainController nodeDrainController() {
            return new NodeDrainController();
        }
    }

    @RestController
    @RequestMapping("/api/admin")
    static class AdminInviteController {
        @PostMapping("/node-invites")
        ResponseEntity<Void> createInvite() {
            return ResponseEntity.status(HttpStatus.CREATED).build();
        }
    }

    @RestController
    @RequestMapping("/api/nodes")
    static class NodeDrainController {
        @PostMapping("/{nodeId}/drain")
        @PreAuthorize("@projectAuthorization.canDrainNode(authentication, #nodeId)")
        ResponseEntity<Void> drain(@PathVariable String nodeId) {
            return ResponseEntity.accepted().build();
        }
    }
}
