package com.company.loopengine.node.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
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
class NodeAdminControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private UUID ownedNodeId;

    @BeforeEach
    void setUp() {
        jdbc.sql("delete from node_certificate").update();
        jdbc.sql("delete from node_heartbeat").update();
        jdbc.sql("delete from repair_node").update();
        jdbc.sql("delete from node_invite").update();
        jdbc.sql("delete from outbox_event").update();
        jdbc.sql("delete from audit_log").update();

        ownedNodeId = UUID.fromString("22222222-2222-2222-2222-222222222201");
        jdbc.sql("""
            insert into repair_node (
              id, name, owner_id, public_key_sha256, certificate_serial, runner_tag, state,
              desired_config_json, concurrency_limit, allowed_projects_json, capabilities_json,
              runner_id)
            values (
              :id, 'node-owned', 'user-NODE_OWNER', repeat('a', 64), 'serial-owned', 'tag-owned',
              'ONLINE', '{"concurrency":1,"allowedProjects":[],"drain":false}'::jsonb, 1,
              '[]'::jsonb, '{"os":"linux","arch":"amd64"}'::jsonb, 99)
            """)
                .param("id", ownedNodeId)
                .update();
        jdbc.sql("""
            insert into node_certificate(
              node_id, serial, public_key_sha256, status, not_before, not_after, created_at)
            values (
              :id, 'serial-owned', repeat('a', 64), 'ACTIVE', :now, :now, :now)
            """)
                .param("id", ownedNodeId)
                .param("now", Timestamp.from(Instant.parse("2026-07-20T10:00:00Z")))
                .update();
    }

    @Test
    void createInviteReturnsOneTimeCodeAndAudit() throws Exception {
        MvcResult result = mvc.perform(post("/api/admin/node-invites")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {
                              "reason": "onboard laptop node",
                              "allowedProjects": ["backend-a"]
                            }
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isString())
                .andExpect(jsonPath("$.joinCommand").value(org.hamcrest.Matchers.containsString("repair-node join")))
                .andExpect(jsonPath("$.auditId").isNumber())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("repair-node join");
        assertThat(jdbc.sql("select count(*) from node_invite").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'NODE_INVITE_CREATED'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void drainRequiresOwnershipAndWritesAudit() throws Exception {
        mvc.perform(post("/api/nodes/{id}/drain", ownedNodeId)
                        .with(user("user-NODE_OWNER").roles("NODE_OWNER"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {"reason": "pause after current jobs"}
                            """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.auditId").isNumber());

        String config = jdbc.sql("select desired_config_json::text from repair_node where id = :id")
                .param("id", ownedNodeId)
                .query(String.class)
                .single();
        assertThat(config).contains("\"drain\": true");

        mvc.perform(post("/api/nodes/node-other/drain")
                        .with(user("user-NODE_OWNER").roles("NODE_OWNER"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {"reason": "pause after current jobs"}
                            """))
                .andExpect(status().isForbidden());
    }

    @Test
    void disableRevokesCertificatesAndEnqueuesRunnerDelete() throws Exception {
        mvc.perform(post("/api/nodes/{id}/disable", ownedNodeId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {"reason": "retire compromised device"}
                            """))
                .andExpect(status().isAccepted());

        assertThat(jdbc.sql("select status from node_certificate where node_id = :id")
                        .param("id", ownedNodeId)
                        .query(String.class)
                        .single())
                .isEqualTo("REVOKED");
        assertThat(jdbc.sql("select enabled from repair_node where id = :id")
                        .param("id", ownedNodeId)
                        .query(Boolean.class)
                        .single())
                .isFalse();
        assertThat(jdbc.sql("select count(*) from outbox_event where event_type = 'gitlab.runner.delete'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void certificateRotationSetsDesiredAction() throws Exception {
        mvc.perform(post("/api/nodes/{id}/certificate-rotation", ownedNodeId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {"reason": "rotate before expiry window"}
                            """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.desiredRevision").value(2));

        String config = jdbc.sql("select desired_config_json::text from repair_node where id = :id")
                .param("id", ownedNodeId)
                .query(String.class)
                .single();
        assertThat(config).contains("certificateRotationRequested");
    }

    @Test
    void configurationPatchPublishesNewRevision() throws Exception {
        mvc.perform(patch("/api/nodes/{id}/configuration", ownedNodeId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {
                              "reason": "raise concurrency for backlog",
                              "concurrency": 4,
                              "allowedProjects": ["backend-a"],
                              "drain": false
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.desiredRevision").value(2));

        assertThat(jdbc.sql("select concurrency_limit from repair_node where id = :id")
                        .param("id", ownedNodeId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(4);
    }
}
