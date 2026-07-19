package com.company.loopengine.scheduling;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.loopengine.gitlab.pipeline.RepairPipelineClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

@Testcontainers
@SpringBootTest
class ReservationReaperTest {
    private static final long CENTRAL_PROJECT_ID = 4242L;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private WireMockServer gitlab;
    private MutableClock clock;
    private ReservationReaper reaper;
    private UUID reservationId;
    private UUID taskId;
    private UUID nodeId;

    @BeforeEach
    void setUp() {
        gitlab = new WireMockServer(wireMockConfig().dynamicPort());
        gitlab.start();
        cleanup();
        clock = new MutableClock(T0.plusSeconds(130));
        reaper = new ReservationReaper(
            jdbc,
            transactionManager,
            new RepairPipelineClient(gitlab.baseUrl(), "glpat-token", CENTRAL_PROJECT_ID),
            clock);
        nodeId = insertNode(1);
        taskId = insertReservedTask();
        reservationId = insertActiveReservation(taskId, nodeId, 777L, T0, T0.plusSeconds(120));
    }

    @AfterEach
    void tearDown() {
        gitlab.stop();
    }

    @Test
    void cancelsPendingPipelineAndReleasesSlot() {
        gitlab.stubFor(get(urlEqualTo("/api/v4/projects/" + CENTRAL_PROJECT_ID + "/pipelines/777"))
            .inScenario("cancel")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(okJson("{\"id\":777,\"status\":\"pending\"}"))
            .willSetStateTo("cancelled"));
        gitlab.stubFor(get(urlEqualTo("/api/v4/projects/" + CENTRAL_PROJECT_ID + "/pipelines/777/jobs"))
            .willReturn(okJson("[{\"id\":1,\"status\":\"pending\"}]")));
        gitlab.stubFor(post(urlEqualTo("/api/v4/projects/" + CENTRAL_PROJECT_ID + "/pipelines/777/cancel"))
            .willReturn(okJson("{\"id\":777,\"status\":\"canceled\"}")));
        gitlab.stubFor(get(urlEqualTo("/api/v4/projects/" + CENTRAL_PROJECT_ID + "/pipelines/777"))
            .inScenario("cancel")
            .whenScenarioStateIs("cancelled")
            .willReturn(okJson("{\"id\":777,\"status\":\"canceled\"}")));

        reaper.reap();

        gitlab.verify(postRequestedFor(
            urlEqualTo("/api/v4/projects/" + CENTRAL_PROJECT_ID + "/pipelines/777/cancel")));
        assertThat(jdbc.sql("select state from task_reservation where id = :id")
            .param("id", reservationId)
            .query(String.class)
            .single()).isEqualTo("EXPIRED");
        assertThat(jdbc.sql("select state from repair_task where id = :id")
            .param("id", taskId)
            .query(String.class)
            .single()).isEqualTo("QUEUED");
        assertThat(jdbc.sql("select active_slots from repair_node where id = :id")
            .param("id", nodeId)
            .query(Integer.class)
            .single()).isEqualTo(0);
    }

    @Test
    void extendsWhenJobIsRunning() {
        gitlab.stubFor(get(urlEqualTo("/api/v4/projects/" + CENTRAL_PROJECT_ID + "/pipelines/777"))
            .willReturn(okJson("{\"id\":777,\"status\":\"running\"}")));
        gitlab.stubFor(get(urlEqualTo("/api/v4/projects/" + CENTRAL_PROJECT_ID + "/pipelines/777/jobs"))
            .willReturn(okJson("[{\"id\":1,\"status\":\"running\"}]")));

        reaper.reap();

        Instant expiresAt = jdbc.sql("select expires_at from task_reservation where id = :id")
            .param("id", reservationId)
            .query((rs, rowNum) -> rs.getTimestamp("expires_at").toInstant())
            .single();
        assertThat(expiresAt).isEqualTo(clock.instant().plusSeconds(60));
        assertThat(jdbc.sql("select state from task_reservation where id = :id")
            .param("id", reservationId)
            .query(String.class)
            .single()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("select active_slots from repair_node where id = :id")
            .param("id", nodeId)
            .query(Integer.class)
            .single()).isEqualTo(1);
    }

    @Test
    void marksExpiryPendingWhenGitlabUnreachable() {
        gitlab.stubFor(get(urlEqualTo("/api/v4/projects/" + CENTRAL_PROJECT_ID + "/pipelines/777"))
            .willReturn(aResponse().withStatus(503).withBody("down")));

        reaper.reap();

        assertThat(jdbc.sql("select state from task_reservation where id = :id")
            .param("id", reservationId)
            .query(String.class)
            .single()).isEqualTo("EXPIRY_PENDING");
        assertThat(jdbc.sql("select active_slots from repair_node where id = :id")
            .param("id", nodeId)
            .query(Integer.class)
            .single()).isEqualTo(1);
        assertThat(jdbc.sql("select state from repair_task where id = :id")
            .param("id", taskId)
            .query(String.class)
            .single()).isEqualTo("RESERVED");
    }

    private UUID insertNode(int activeSlots) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            insert into repair_node(
              id, name, owner_id, public_key_sha256, certificate_serial, runner_tag,
              state, enabled, desired_revision, applied_revision, desired_config_json,
              concurrency_limit, active_slots, allowed_projects_json, capabilities_json)
            values (
              :id, 'n', 'o', :pk, :serial, :tag,
              'ONLINE', true, 1, 1, cast('{"concurrency":1,"allowedProjects":["backend-a"],"drain":false}' as jsonb),
              2, :slots, cast('["backend-a"]' as jsonb), cast('{"os":"linux","arch":"amd64","java":"21"}' as jsonb))
            """)
            .param("id", id)
            .param("pk", "d".repeat(64))
            .param("serial", "serial-" + id)
            .param("tag", "repair-node-" + id)
            .param("slots", activeSlots)
            .update();
        return id;
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
              't', 'd', 'QUEUED', :now, 0)
            """)
            .param("id", defectId)
            .param("iid", iid)
            .param("url", "https://gitlab.example/i/" + iid)
            .param("now", Timestamp.from(T0))
            .update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            insert into repair_task(
              id, defect_id, defect_revision, project_key, profile_revision,
              profile_snapshot_json, base_sha, state, priority, created_at)
            values (
              :id, :defectId, 1, 'backend-a', 1,
              cast('{"allowedOs":["linux"]}' as jsonb), 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
              'RESERVED', 10, :createdAt)
            """)
            .param("id", id)
            .param("defectId", defectId)
            .param("createdAt", Timestamp.from(T0))
            .update();
        return id;
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

    private void cleanup() {
        jdbc.sql("delete from task_reservation").update();
        jdbc.sql("delete from repair_task").update();
        jdbc.sql("delete from defect").update();
        jdbc.sql("delete from repair_node").update();
    }

    private static final class MutableClock extends Clock {
        private final Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

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
            return instant;
        }
    }
}
