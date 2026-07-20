package com.company.loopengine.execution;

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
class TaskAdminControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant T0 = Instant.parse("2026-07-20T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private UUID taskId;
    private UUID outboxId;
    private String webhookUuid;

    @BeforeEach
    void setUp() {
        jdbc.sql("delete from repair_attempt").update();
        jdbc.sql("delete from task_reservation").update();
        jdbc.sql("delete from repair_task").update();
        jdbc.sql("delete from defect_transition").update();
        jdbc.sql("delete from defect_attachment").update();
        jdbc.sql("delete from defect").update();
        jdbc.sql("delete from project_profile_owner").update();
        jdbc.sql("delete from project_profile_revision").update();
        jdbc.sql("delete from project_profile").update();
        jdbc.sql("delete from outbox_event").update();
        jdbc.sql("delete from gitlab_webhook_delivery").update();
        jdbc.sql("delete from audit_log").update();

        jdbc.sql("""
            insert into project_profile (id, project_key, gitlab_path)
            values ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 'backend-a', 'group/backend-a')
            """)
                .update();
        jdbc.sql("""
            insert into project_profile_owner (profile_id, gitlab_user_id, added_by)
            values ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 1001, 'seed')
            """)
                .update();

        UUID defectId = UUID.randomUUID();
        jdbc.sql("""
            insert into defect(
              id, intake_project_id, issue_iid, issue_global_id, issue_url,
              title, description, state, source_updated_at, version, created_at)
            values (
              :id, 1, 12, 12, 'https://gitlab.example/group/defect-intake/-/issues/12',
              'title', 'desc', 'FAILED', :now, 0, :now)
            """)
                .param("id", defectId)
                .param("now", Timestamp.from(T0))
                .update();

        taskId = UUID.randomUUID();
        jdbc.sql("""
            insert into repair_task(
              id, defect_id, defect_revision, project_key, profile_revision,
              profile_snapshot_json, base_sha, state, priority, created_at)
            values (
              :id, :defectId, 1, 'backend-a', 1,
              '{}'::jsonb, repeat('a', 40), 'FAILED', 100, :createdAt)
            """)
                .param("id", taskId)
                .param("defectId", defectId)
                .param("createdAt", Timestamp.from(T0))
                .update();

        outboxId = UUID.randomUUID();
        jdbc.sql("""
            insert into outbox_event(
              id, aggregate_type, aggregate_id, event_type, payload_json, occurred_at,
              processed_at, last_error)
            values (
              :id, 'DEFECT', 'd1', 'gitlab.comment', '{}'::jsonb, :occurredAt,
              :occurredAt, 'temporary failure')
            """)
                .param("id", outboxId)
                .param("occurredAt", Timestamp.from(T0))
                .update();

        webhookUuid = "wh-" + UUID.randomUUID();
        jdbc.sql("""
            insert into gitlab_webhook_delivery(
              event_uuid, event_name, payload_json, received_at, processing_state,
              attempt_count, last_error)
            values (
              :uuid, 'Issue Hook', '{}'::jsonb, :receivedAt, 'FAILED', 3, 'boom')
            """)
                .param("uuid", webhookUuid)
                .param("receivedAt", Timestamp.from(T0))
                .update();
    }

    @Test
    void retryFailedTaskRequiresReasonAndWritesAudit() throws Exception {
        mvc.perform(post("/api/tasks/{id}/retry", taskId)
                        .with(user("1001").roles("PROJECT_OWNER"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {"reason": "operator confirmed root cause fixed"}
                            """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.auditId").isNumber());

        assertThat(jdbc.sql("select state from repair_task where id = :id")
                        .param("id", taskId)
                        .query(String.class)
                        .single())
                .isEqualTo("QUEUED");
    }

    @Test
    void cancelRejectsShortReason() throws Exception {
        mvc.perform(post("/api/tasks/{id}/cancel", taskId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {"reason": "short"}
                            """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelTaskWritesAuditId() throws Exception {
        mvc.perform(post("/api/tasks/{id}/cancel", taskId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {"reason": "human takeover requested now"}
                            """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.auditId").isNumber());
    }

    @Test
    void retryOutboxAndWebhookRequireAdminReason() throws Exception {
        mvc.perform(post("/api/operations/outbox/{id}/retry", outboxId)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {"reason": "replay after gitlab recovered"}
                            """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.auditId").isNumber());

        assertThat(jdbc.sql("select processed_at from outbox_event where id = :id")
                        .param("id", outboxId)
                        .query(Timestamp.class)
                        .optional())
                .isEmpty();

        mvc.perform(post("/api/operations/webhooks/{id}/retry", webhookUuid)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {"reason": "replay after gitlab recovered"}
                            """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.auditId").isNumber());

        assertThat(jdbc.sql("select processing_state from gitlab_webhook_delivery where event_uuid = :id")
                        .param("id", webhookUuid)
                        .query(String.class)
                        .single())
                .isEqualTo("RETRY");
    }

    @Test
    void observerCannotRetryOutbox() throws Exception {
        mvc.perform(post("/api/operations/outbox/{id}/retry", outboxId)
                        .with(user("9001").roles("OBSERVER"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("""
                            {"reason": "replay after gitlab recovered"}
                            """))
                .andExpect(status().isForbidden());
    }
}
