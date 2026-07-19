package com.company.loopengine.scheduling;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.loopengine.gitlab.pipeline.RepairPipelineClient;
import com.company.loopengine.scheduling.Scheduler.ScheduleResult;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
class SchedulerConcurrencyTest {
    private static final long CENTRAL_PROJECT_ID = 4242L;
    private static final AtomicInteger PIPELINE_IDS = new AtomicInteger(1000);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private WireMockServer gitlab;
    private Scheduler scheduler;
    private UUID taskId;
    private UUID nodeId;

    @BeforeEach
    void setUp() {
        gitlab = new WireMockServer(wireMockConfig().dynamicPort());
        gitlab.start();
        cleanup();
        gitlab.stubFor(post(urlPathMatching("/api/v4/projects/.*/pipeline"))
            .willReturn(okJson("{\"id\":" + PIPELINE_IDS.incrementAndGet() + "}")));
        scheduler = new Scheduler(
            jdbc,
            transactionManager,
            new RepairPipelineClient(gitlab.baseUrl(), "glpat-token", CENTRAL_PROJECT_ID),
            new NodeMatcher());
        nodeId = onlineNodeWithSlots(1);
        taskId = queuedTask();
    }

    @AfterEach
    void tearDown() {
        gitlab.stop();
    }

    @Test
    void concurrentSchedulersReserveAFreeSlotOnlyOnce() throws Exception {
        UUID task = taskId;
        UUID node = nodeId;
        List<ScheduleResult> results = runConcurrently(2, () -> scheduler.scheduleOne());
        assertThat(results).filteredOn(r -> r.taskId().equals(task)).hasSize(1);
        assertThat(reservationsActiveFor(node)).hasSize(1);
        assertThat(nodeActiveSlots(node)).isEqualTo(1);
    }

    @Test
    void pipelineTriggerContainsExactlyOneNodeTag() {
        Optional<ScheduleResult> result = scheduler.scheduleOne();
        assertThat(result).isPresent();
        assertThat(result.get().taskId()).isEqualTo(taskId);
        assertThat(result.get().pipelineId()).isNotNull();

        gitlab.verify(postRequestedFor(urlEqualTo("/api/v4/projects/" + CENTRAL_PROJECT_ID + "/pipeline"))
            .withRequestBody(matchingJsonPath("$.variables[?(@.key=='LOOP_RUNNER_TAG')].value",
                equalTo("repair-node-" + nodeId)))
            .withRequestBody(matchingJsonPath("$.variables[?(@.key=='LOOP_TASK_ID')].value",
                equalTo(taskId.toString())))
            .withRequestBody(matchingJsonPath("$.variables[?(@.key=='LOOP_NODE_ID')].value",
                equalTo(nodeId.toString())))
            .withRequestBody(containing("LOOP_RESERVATION_ID")));
        assertThat(gitlab.findAll(postRequestedFor(
                urlEqualTo("/api/v4/projects/" + CENTRAL_PROJECT_ID + "/pipeline"))))
            .hasSize(1);
    }

    @Test
    void definite4xxCompensatesByReleasingReservation() {
        gitlab.resetAll();
        gitlab.stubFor(post(urlPathMatching("/api/v4/projects/.*/pipeline"))
            .willReturn(aResponse().withStatus(400).withBody("{\"message\":\"bad\"}")));

        Optional<ScheduleResult> result = scheduler.scheduleOne();
        assertThat(result).isEmpty();
        assertThat(jdbc.sql("select state from repair_task where id = :id")
            .param("id", taskId)
            .query(String.class)
            .single()).isEqualTo("QUEUED");
        assertThat(reservationsActiveFor(nodeId)).isEmpty();
        assertThat(nodeActiveSlots(nodeId)).isEqualTo(0);
    }

    private List<ScheduleResult> runConcurrently(int threads, Callable<Optional<ScheduleResult>> action)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Future<Optional<ScheduleResult>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    return action.call();
                } finally {
                    done.countDown();
                }
            }));
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        List<ScheduleResult> results = new ArrayList<>();
        for (Future<Optional<ScheduleResult>> future : futures) {
            future.get(5, TimeUnit.SECONDS).ifPresent(results::add);
        }
        return results;
    }

    private UUID queuedTask() {
        UUID defectId = UUID.randomUUID();
        long iid = Math.abs(defectId.getLeastSignificantBits() % 1_000_000);
        jdbc.sql("""
            insert into defect(
              id, intake_project_id, issue_iid, issue_global_id, issue_url,
              title, description, state, source_updated_at, version)
            values (
              :id, 1, :iid, :iid, :url,
              'bug', 'desc', 'QUEUED', :now, 0)
            """)
            .param("id", defectId)
            .param("iid", iid)
            .param("url", "https://gitlab.example/issue/" + iid)
            .param("now", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")))
            .update();

        UUID id = UUID.randomUUID();
        String snapshot = """
            {"projectKey":"backend-a","allowedOs":["linux"],"allowedArch":["amd64"],
             "requiredTools":{"java":">=21"},"minMemoryBytes":1073741824,"minDiskBytes":1073741824,
             "allowedNodeIds":[],"allowedNodeOwnerIds":[]}
            """.replaceAll("\\s+", "");
        jdbc.sql("""
            insert into repair_task(
              id, defect_id, defect_revision, project_key, profile_revision,
              profile_snapshot_json, base_sha, state, priority)
            values (
              :id, :defectId, 1, 'backend-a', 1,
              cast(:snapshot as jsonb), 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'QUEUED', 10)
            """)
            .param("id", id)
            .param("defectId", defectId)
            .param("snapshot", snapshot)
            .update();
        return id;
    }

    private UUID onlineNodeWithSlots(int concurrencyLimit) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            insert into repair_node(
              id, name, owner_id, public_key_sha256, certificate_serial, runner_tag,
              state, enabled, desired_revision, applied_revision, desired_config_json,
              concurrency_limit, active_slots, allowed_projects_json, capabilities_json)
            values (
              :id, 'node-1', 'owner-1', :pk, :serial, :tag,
              'ONLINE', true, 1, 1, cast('{"concurrency":1,"allowedProjects":["backend-a"],"drain":false}' as jsonb),
              :limit, 0, cast('["backend-a"]' as jsonb),
              cast(:caps as jsonb))
            """)
            .param("id", id)
            .param("pk", "b".repeat(64))
            .param("serial", "serial-" + id)
            .param("tag", "repair-node-" + id)
            .param("limit", concurrencyLimit)
            .param("caps", """
                {"os":"linux","arch":"amd64","java":"21","memoryAvailableBytes":8589934592,
                 "diskAvailableBytes":107374182400,"hasRepoCache":false,"failureRatePermille":100}
                """.replaceAll("\\s+", ""))
            .update();
        return id;
    }

    private List<UUID> reservationsActiveFor(UUID node) {
        return jdbc.sql("""
            select id from task_reservation
            where node_id = :nodeId and state = 'ACTIVE'
            """)
            .param("nodeId", node)
            .query(UUID.class)
            .list();
    }

    private int nodeActiveSlots(UUID node) {
        return jdbc.sql("select active_slots from repair_node where id = :id")
            .param("id", node)
            .query(Integer.class)
            .single();
    }

    private void cleanup() {
        jdbc.sql("delete from task_reservation").update();
        jdbc.sql("delete from repair_task").update();
        jdbc.sql("delete from defect_attachment").update();
        jdbc.sql("delete from defect_transition").update();
        jdbc.sql("delete from defect").update();
        jdbc.sql("delete from node_heartbeat").update();
        jdbc.sql("delete from repair_node").update();
        jdbc.sql("delete from audit_log").update();
    }
}
