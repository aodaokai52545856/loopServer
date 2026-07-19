package com.company.loopengine.gitlab.pipeline;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.loopengine.gitlab.pipeline.RepairPipelineClient.PipelineAmbiguousException;
import com.company.loopengine.gitlab.pipeline.RepairPipelineClient.PipelineTerminalException;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RepairPipelineClientTest {
    private static final long CENTRAL_PROJECT_ID = 4242L;

    private WireMockServer gitlab;
    private RepairPipelineClient client;

    @BeforeEach
    void setUp() {
        gitlab = new WireMockServer(wireMockConfig().dynamicPort());
        gitlab.start();
        client = new RepairPipelineClient(gitlab.baseUrl(), "glpat-token", CENTRAL_PROJECT_ID);
    }

    @AfterEach
    void tearDown() {
        gitlab.stop();
    }

    @Test
    void triggerRequestContainsExactlyOneNodeTagVariable() {
        UUID taskId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        String runnerTag = "repair-node-" + nodeId;
        gitlab.stubFor(post(urlEqualTo("/api/v4/projects/" + CENTRAL_PROJECT_ID + "/pipeline"))
            .willReturn(okJson("{\"id\":555}")));

        long pipelineId = client.triggerRepairPipeline(taskId, reservationId, nodeId, runnerTag);

        assertThat(pipelineId).isEqualTo(555L);
        gitlab.verify(postRequestedFor(urlEqualTo("/api/v4/projects/" + CENTRAL_PROJECT_ID + "/pipeline"))
            .withRequestBody(matchingJsonPath("$.ref", equalTo("main")))
            .withRequestBody(matchingJsonPath("$.variables[?(@.key=='LOOP_TASK_ID')].value",
                equalTo(taskId.toString())))
            .withRequestBody(matchingJsonPath("$.variables[?(@.key=='LOOP_RESERVATION_ID')].value",
                equalTo(reservationId.toString())))
            .withRequestBody(matchingJsonPath("$.variables[?(@.key=='LOOP_NODE_ID')].value",
                equalTo(nodeId.toString())))
            .withRequestBody(matchingJsonPath("$.variables[?(@.key=='LOOP_RUNNER_TAG')].value",
                equalTo(runnerTag))));
        String body = gitlab.findAll(postRequestedFor(
                urlEqualTo("/api/v4/projects/" + CENTRAL_PROJECT_ID + "/pipeline")))
            .getFirst()
            .getBodyAsString();
        assertThat(body.split("LOOP_RUNNER_TAG", -1)).hasSize(2);
    }

    @Test
    void definite4xxIsTerminal() {
        gitlab.stubFor(post(urlEqualTo("/api/v4/projects/" + CENTRAL_PROJECT_ID + "/pipeline"))
            .willReturn(aResponse().withStatus(400).withBody("bad")));

        assertThatThrownBy(() -> client.triggerRepairPipeline(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repair-node-x"))
            .isInstanceOf(PipelineTerminalException.class);
    }

    @Test
    void serverErrorIsAmbiguous() {
        gitlab.stubFor(post(urlEqualTo("/api/v4/projects/" + CENTRAL_PROJECT_ID + "/pipeline"))
            .willReturn(aResponse().withStatus(503).withBody("unavailable")));

        assertThatThrownBy(() -> client.triggerRepairPipeline(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "repair-node-x"))
            .isInstanceOf(PipelineAmbiguousException.class);
    }
}
