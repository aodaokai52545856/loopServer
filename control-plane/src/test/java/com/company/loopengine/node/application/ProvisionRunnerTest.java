package com.company.loopengine.node.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.loopengine.gitlab.runner.RunnerAdminClient;
import com.company.loopengine.node.application.ProvisionRunner.RunnerBootstrap;
import com.company.loopengine.node.application.ProvisionRunner.RunnerProvisionFailed;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
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
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest
class ProvisionRunnerTest {
    private static final long CENTRAL_PROJECT_ID = 4242L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JsonMapper jsonMapper;

    private WireMockServer gitlab;
    private ProvisionRunner service;
    private UUID nodeId;

    @BeforeEach
    void setUp() {
        gitlab = new WireMockServer(wireMockConfig().dynamicPort());
        gitlab.start();
        jdbc.sql("delete from audit_log").update();
        jdbc.sql("delete from repair_node").update();
        nodeId = insertPendingRunnerNode();
        service = new ProvisionRunner(
            jdbc,
            transactionManager,
            new RunnerAdminClient(gitlab.baseUrl(), "glpat-admin-token", CENTRAL_PROJECT_ID));
    }

    @AfterEach
    void tearDown() {
        gitlab.stop();
    }

    @Test
    void createsAProjectRunnerWithOnlyTheUniqueNodeTag() {
        gitlab.stubFor(post(urlEqualTo("/api/v4/user/runners"))
            .willReturn(okJson("{\"id\":77,\"token\":\"glrt-secret\"}")));
        gitlab.stubFor(get(urlEqualTo("/api/v4/runners/77"))
            .willReturn(okJson("""
                {"id":77,"description":"loop-engine:%s","tag_list":["repair-node-%s"],"run_untagged":false,"locked":true}
                """.formatted(nodeId, nodeId))));

        RunnerBootstrap result = service.provision(nodeId);

        assertThat(result.runnerId()).isEqualTo(77);
        assertThat(result.authenticationToken()).isEqualTo("glrt-secret");
        gitlab.verify(postRequestedFor(urlEqualTo("/api/v4/user/runners"))
            .withRequestBody(matchingJsonPath("$.tag_list[0]", equalTo("repair-node-" + nodeId)))
            .withRequestBody(matchingJsonPath("$.run_untagged", equalTo("false")))
            .withRequestBody(matchingJsonPath("$.locked", equalTo("true")))
            .withRequestBody(matchingJsonPath("$.access_level", equalTo("not_protected")))
            .withRequestBody(matchingJsonPath("$.runner_type", equalTo("project_type")))
            .withRequestBody(matchingJsonPath("$.project_id", equalTo(String.valueOf(CENTRAL_PROJECT_ID))))
            .withRequestBody(matchingJsonPath("$.description", equalTo("loop-engine:" + nodeId))));
        gitlab.verify(getRequestedFor(urlEqualTo("/api/v4/runners/77")));
        Long persisted = jdbc.sql("select runner_id from repair_node where id = :id")
            .param("id", nodeId)
            .query(Long.class)
            .single();
        assertThat(persisted).isEqualTo(77L);
    }

    @Test
    void gitlabServerErrorLeavesNodePendingRunnerWithRetryAction() {
        gitlab.stubFor(post(urlEqualTo("/api/v4/user/runners"))
            .willReturn(aResponse().withStatus(503).withBody("unavailable")));

        assertThatThrownBy(() -> service.provision(nodeId))
            .isInstanceOf(RunnerProvisionFailed.class)
            .satisfies(ex -> assertThat(((RunnerProvisionFailed) ex).retryAction()).isTrue());

        String state = jdbc.sql("select state from repair_node where id = :id")
            .param("id", nodeId)
            .query(String.class)
            .single();
        Long runnerId = jdbc.sql("select runner_id from repair_node where id = :id")
            .param("id", nodeId)
            .query((rs, rowNum) -> rs.getObject("runner_id") == null ? null : rs.getLong("runner_id"))
            .optional()
            .orElse(null);
        assertThat(state).isEqualTo("PENDING_RUNNER");
        assertThat(runnerId).isNull();
    }

    @Test
    void doesNotPersistOrAuditTheAuthenticationToken() {
        gitlab.stubFor(post(urlEqualTo("/api/v4/user/runners"))
            .willReturn(okJson("{\"id\":77,\"token\":\"glrt-secret\"}")));
        gitlab.stubFor(get(urlEqualTo("/api/v4/runners/77"))
            .willReturn(okJson("{\"id\":77}")));

        service.provision(nodeId);

        Integer tokenHits = jdbc.sql("""
            select count(*) from repair_node
            where id = :id
              and (
                cast(desired_config_json as text) like '%glrt-secret%'
                or cast(capabilities_json as text) like '%glrt-secret%'
                or cast(allowed_projects_json as text) like '%glrt-secret%'
              )
            """)
            .param("id", nodeId)
            .query(Integer.class)
            .single();
        assertThat(tokenHits).isZero();

        Integer auditHits = jdbc.sql("""
            select count(*) from audit_log
            where cast(detail_json as text) like '%glrt-secret%'
            """)
            .query(Integer.class)
            .single();
        assertThat(auditHits).isZero();
    }

    private UUID insertPendingRunnerNode() {
        UUID id = UUID.randomUUID();
        String projectsJson = jsonMapper.writeValueAsString(List.of("backend-a"));
        String desired = "{\"concurrency\":2,\"allowedProjects\":" + projectsJson + ",\"drain\":false}";
        jdbc.sql("""
            insert into repair_node(
              id, name, owner_id, public_key_sha256, certificate_serial, runner_tag,
              state, desired_revision, applied_revision, desired_config_json, concurrency_limit,
              allowed_projects_json, capabilities_json)
            values (
              :id, 'dev-laptop', 'owner-1', :publicKeySha, :serial, :runnerTag,
              'PENDING_RUNNER', 1, 0, cast(:desired as jsonb), 2,
              cast(:projects as jsonb), cast('{}' as jsonb))
            """)
            .param("id", id)
            .param("publicKeySha", "a".repeat(64))
            .param("serial", "serial-" + id)
            .param("runnerTag", "repair-node-" + id)
            .param("desired", desired)
            .param("projects", projectsJson)
            .update();
        return id;
    }
}
