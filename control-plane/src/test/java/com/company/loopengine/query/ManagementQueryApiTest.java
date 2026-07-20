package com.company.loopengine.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
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
            "loop.gitlab.url=http://gitlab.test",
            "loop.query.cursor-hmac-secret=test-cursor-secret",
            "loop.query.sse-test-complete-after-history=true"
        })
class ManagementQueryApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final Instant T0 = Instant.parse("2026-07-20T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-20T10:01:00Z");
    private static final Instant T2 = Instant.parse("2026-07-20T10:02:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    private UUID taskId;
    private UUID attemptId;
    private UUID visibleTaskId;
    private UUID secretTaskId;

    @BeforeEach
    void setUp() {
        jdbc.sql("delete from task_event").update();
        jdbc.sql("delete from repair_attempt").update();
        jdbc.sql("delete from task_reservation").update();
        jdbc.sql("delete from repair_task").update();
        jdbc.sql("delete from defect_attachment").update();
        jdbc.sql("delete from defect_transition").update();
        jdbc.sql("delete from defect").update();
        jdbc.sql("delete from node_heartbeat").update();
        jdbc.sql("delete from repair_node").update();
        jdbc.sql("delete from project_profile_owner").update();
        jdbc.sql("delete from project_profile_revision").update();
        jdbc.sql("delete from project_profile").update();
        jdbc.sql("delete from gitlab_webhook_delivery").update();
        jdbc.sql("delete from outbox_event").update();
        jdbc.sql("delete from audit_log").update();

        seedProjectsAndVisibility();
        UUID nodeId = seedNode();
        visibleTaskId = seedTask("backend-a", "RUNNING", T1);
        secretTaskId = seedTask("backend-secret", "RUNNING", T2);
        taskId = visibleTaskId;
        attemptId = seedAttempt(taskId, nodeId);
    }

    @Test
    void taskListUsesStableCursorPagination() throws Exception {
        mvc.perform(get("/api/tasks?state=RUNNING&limit=20").with(observer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.nextCursor").exists());
    }

    @Test
    void eventStreamResumesAfterLastEventId() throws Exception {
        appendEvents(attemptId, 1, 2, 3, 4);
        MvcResult result = mvc.perform(get("/api/tasks/{id}/events", taskId)
                        .header("Last-Event-ID", attemptId + ":2")
                        .accept(TEXT_EVENT_STREAM)
                        .with(observer()))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("id:" + attemptId + ":3").doesNotContain("id:" + attemptId + ":2");
    }

    @Test
    void rejectsTamperedCursor() throws Exception {
        MvcResult ok = mvc.perform(get("/api/tasks?state=RUNNING&limit=1").with(observer()))
                .andExpect(status().isOk())
                .andReturn();
        String body = ok.getResponse().getContentAsString();
        int start = body.indexOf("\"nextCursor\":\"") + "\"nextCursor\":\"".length();
        int end = body.indexOf('"', start);
        String cursor = body.substring(start, end);
        String tampered = cursor.substring(0, Math.max(0, cursor.length() - 4)) + "dead";
        mvc.perform(get("/api/tasks")
                        .param("state", "RUNNING")
                        .param("limit", "1")
                        .param("cursor", tampered)
                        .with(observer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void observerCannotSeeUnauthorizedProjectTasksOrDashboardCounts() throws Exception {
        mvc.perform(get("/api/tasks?state=RUNNING&limit=50").with(observer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(visibleTaskId.toString()));

        mvc.perform(get("/api/tasks/{id}", secretTaskId).with(observer()))
                .andExpect(status().isNotFound());

        MvcResult dash = mvc.perform(get("/api/dashboard").with(observer()))
                .andExpect(status().isOk())
                .andReturn();
        String body = dash.getResponse().getContentAsString();
        assertThat(body).contains("RUNNING");
        assertThat(body).doesNotContain(secretTaskId.toString());
        assertThat(body).doesNotContain("backend-secret");
    }

    @Test
    void observerCannotStreamUnauthorizedTaskEvents() throws Exception {
        UUID secretAttempt = seedAttempt(secretTaskId, seedNodeNamed("secret-node"));
        appendEvents(secretAttempt, 1, 2);
        mvc.perform(get("/api/tasks/{id}/events", secretTaskId)
                        .accept(TEXT_EVENT_STREAM)
                        .with(observer()))
                .andExpect(status().isNotFound());
    }

    private RequestPostProcessor observer() {
        return user("9001").roles("OBSERVER");
    }

    private void seedProjectsAndVisibility() {
        jdbc.sql(
                        """
            insert into project_profile (id, project_key, gitlab_path)
            values
              ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 'backend-a', 'group/backend-a'),
              ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', 'backend-secret', 'group/backend-secret')
            """)
                .update();
        jdbc.sql(
                        """
            insert into project_profile_owner (profile_id, gitlab_user_id, added_by)
            values ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 9001, 'seed')
            """)
                .update();
        jdbc.sql(
                        """
            insert into project_profile_revision (
              profile_id, revision, config_json, config_sha256, published_by, published_at)
            values (
              'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 1,
              '{"repository":"group/backend-a"}'::jsonb,
              repeat('a', 64), 'seed', :publishedAt)
            """)
                .param("publishedAt", Timestamp.from(T0))
                .update();
    }

    private UUID seedNode() {
        return seedNodeNamed("query-node");
    }

    private UUID seedNodeNamed(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        """
            insert into repair_node (
              id, name, owner_id, public_key_sha256, certificate_serial, runner_tag, state,
              desired_config_json, concurrency_limit, allowed_projects_json, capabilities_json,
              created_at)
            values (
              :id, :name, 'owner-1', :pk, :serial, :tag, 'ONLINE',
              '{"concurrency":1,"allowedProjects":["backend-a"],"drain":false}'::jsonb, 1,
              '["backend-a"]'::jsonb, '{"os":"linux"}'::jsonb, :createdAt)
            """)
                .param("id", id)
                .param("name", name)
                .param("pk", HexPad.pad(name, 64))
                .param("serial", "serial-" + name)
                .param("tag", "tag-" + name)
                .param("createdAt", Timestamp.from(T0))
                .update();
        return id;
    }

    private UUID seedTask(String projectKey, String state, Instant createdAt) {
        UUID defectId = UUID.randomUUID();
        long iid = Math.abs(defectId.getLeastSignificantBits() % 1_000_000);
        jdbc.sql(
                        """
            insert into defect(
              id, intake_project_id, issue_iid, issue_global_id, issue_url,
              title, description, state, source_updated_at, version, created_at)
            values (
              :id, 1, :iid, :iid, :url,
              'title', 'desc', 'QUEUED', :now, 0, :now)
            """)
                .param("id", defectId)
                .param("iid", iid)
                .param("url", "https://gitlab.example/group/defect-intake/-/issues/" + iid)
                .param("now", Timestamp.from(createdAt))
                .update();
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        """
            insert into repair_task(
              id, defect_id, defect_revision, project_key, profile_revision,
              profile_snapshot_json, base_sha, state, priority, created_at)
            values (
              :id, :defectId, 1, :projectKey, 1,
              '{"repository":"group/x"}'::jsonb, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
              :state, 10, :createdAt)
            """)
                .param("id", id)
                .param("defectId", defectId)
                .param("projectKey", projectKey)
                .param("state", state)
                .param("createdAt", Timestamp.from(createdAt))
                .update();
        return id;
    }

    private UUID seedAttempt(UUID task, UUID node) {
        UUID reservationId = UUID.randomUUID();
        jdbc.sql(
                        """
            insert into task_reservation(id, task_id, node_id, pipeline_id, expires_at, state, created_at)
            values (:id, :taskId, :nodeId, 9, :expiresAt, 'ACTIVE', :createdAt)
            """)
                .param("id", reservationId)
                .param("taskId", task)
                .param("nodeId", node)
                .param("expiresAt", Timestamp.from(T2.plusSeconds(120)))
                .param("createdAt", Timestamp.from(T0))
                .update();
        UUID attempt = UUID.randomUUID();
        long jobId = Math.abs(attempt.getLeastSignificantBits() % 1_000_000_000L);
        jdbc.sql(
                        """
            insert into repair_attempt(
              id, task_id, attempt_no, node_id, reservation_id, pipeline_id, job_id,
              state, task_token_hash, lease_expires_at, started_at)
            values (
              :id, :taskId, 1, :nodeId, :reservationId, 9, :jobId,
              'RUNNING', :tokenHash, :lease, :startedAt)
            """)
                .param("id", attempt)
                .param("taskId", task)
                .param("nodeId", node)
                .param("reservationId", reservationId)
                .param("jobId", jobId)
                .param("tokenHash", HexPad.pad(attempt.toString(), 64))
                .param("lease", Timestamp.from(T2.plusSeconds(600)))
                .param("startedAt", Timestamp.from(T0))
                .update();
        return attempt;
    }

    private void appendEvents(UUID attempt, long... seqs) {
        for (long seq : seqs) {
            jdbc.sql(
                            """
                insert into task_event(attempt_id, seq, event_time, received_at, type, payload_json)
                values (:attemptId, :seq, :eventTime, :receivedAt, :type, '{}'::jsonb)
                """)
                    .param("attemptId", attempt)
                    .param("seq", seq)
                    .param("eventTime", Timestamp.from(T0.plusSeconds(seq)))
                    .param("receivedAt", Timestamp.from(T0.plusSeconds(seq)))
                    .param("type", "agent.step")
                    .update();
        }
    }

    private static final class HexPad {
        private static String pad(String seed, int len) {
            String hex = seed.replace("-", "");
            StringBuilder sb = new StringBuilder();
            while (sb.length() < len) {
                sb.append(hex);
            }
            return sb.substring(0, len);
        }
    }
}
