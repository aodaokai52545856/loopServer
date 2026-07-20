package com.company.loopengine.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
class AuditCoverageTest {

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
        jdbc.sql("delete from audit_log").update();

        ownedNodeId = UUID.fromString("22222222-2222-2222-2222-222222222255");
        jdbc.sql("""
            insert into repair_node (
              id, name, owner_id, public_key_sha256, certificate_serial, runner_tag, state,
              desired_config_json, concurrency_limit, allowed_projects_json, capabilities_json,
              runner_id)
            values (
              :id, 'node-audit', 'user-NODE_OWNER', repeat('b', 64), 'serial-audit', 'tag-audit',
              'ONLINE', '{"concurrency":1,"allowedProjects":[],"drain":false}'::jsonb, 1,
              '[]'::jsonb, '{"os":"linux","arch":"amd64"}'::jsonb, 88)
            """)
                .param("id", ownedNodeId)
                .update();
        jdbc.sql("""
            insert into node_certificate(
              node_id, serial, public_key_sha256, status, not_before, not_after, created_at)
            values (
              :id, 'serial-audit', repeat('b', 64), 'ACTIVE', :now, :now, :now)
            """)
                .param("id", ownedNodeId)
                .param("now", Timestamp.from(Instant.parse("2026-07-20T10:00:00Z")))
                .update();
    }

    @Test
    void managementMutationEmitsActorActionObjectReasonAndRequestId() throws Exception {
        String requestId = "audit-req-coverage-001";
        mvc.perform(post("/api/nodes/{id}/drain", ownedNodeId)
                        .header("X-Request-Id", requestId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {"reason": "coverage pause for audit fields"}
                            """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.auditId").isNumber());

        assertThat(jdbc.sql("select actor_type from audit_log where request_id = :requestId")
                        .param("requestId", requestId)
                        .query(String.class)
                        .single())
                .isEqualTo("USER");
        assertThat(jdbc.sql("select actor_id from audit_log where request_id = :requestId")
                        .param("requestId", requestId)
                        .query(String.class)
                        .single())
                .isEqualTo("admin");
        assertThat(jdbc.sql("select action from audit_log where request_id = :requestId")
                        .param("requestId", requestId)
                        .query(String.class)
                        .single())
                .isEqualTo("NODE_DRAIN");
        assertThat(jdbc.sql("select object_type from audit_log where request_id = :requestId")
                        .param("requestId", requestId)
                        .query(String.class)
                        .single())
                .isEqualTo("REPAIR_NODE");
        assertThat(jdbc.sql("select object_id from audit_log where request_id = :requestId")
                        .param("requestId", requestId)
                        .query(String.class)
                        .single())
                .isEqualTo(ownedNodeId.toString());
        assertThat(jdbc.sql("select request_id from audit_log where request_id = :requestId")
                        .param("requestId", requestId)
                        .query(String.class)
                        .single())
                .isEqualTo(requestId);
        String detail = jdbc.sql("select detail_json::text from audit_log where request_id = :requestId")
                .param("requestId", requestId)
                .query(String.class)
                .single();
        assertThat(detail).contains("coverage pause for audit fields").doesNotContain("glpat-");
    }
}
