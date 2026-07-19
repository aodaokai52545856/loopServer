package com.company.loopengine.gitlab.outbox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.loopengine.gitlab.client.HttpGitLabClient;
import com.company.loopengine.gitlab.outbox.GitLabOutboxHandler.DefectIssueLocator;
import com.company.loopengine.shared.outbox.OutboxEvent;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitLabOutboxHandlerTest {
    private WireMockServer gitlab;
    private GitLabOutboxHandler handler;
    private UUID knownDefectId;

    @BeforeEach
    void setUp() {
        gitlab = new WireMockServer(wireMockConfig().dynamicPort());
        gitlab.start();
        knownDefectId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        DefectIssueLocator locator = defectId -> knownDefectId.equals(defectId)
            ? Optional.of(new DefectIssueLocator.IssueRef(9001L, 12L))
            : Optional.empty();
        handler = new GitLabOutboxHandler(
            new HttpGitLabClient(gitlab.baseUrl(), "glpat-test-token"),
            locator);
    }

    @AfterEach
    void tearDown() {
        gitlab.stop();
    }

    @Test
    void replacesOnlyRepairScopedLabelAndPostsIdempotentNote() {
        gitlab.stubFor(get(urlEqualTo("/api/v4/projects/9001/issues/12"))
            .willReturn(okJson("""
                {"labels":["bug","repair::new","priority"]}
                """)));
        gitlab.stubFor(put(urlEqualTo("/api/v4/projects/9001/issues/12"))
            .willReturn(okJson("{}")));
        gitlab.stubFor(get(urlEqualTo("/api/v4/projects/9001/issues/12/notes?per_page=100"))
            .willReturn(okJson("[]")));
        gitlab.stubFor(post(urlEqualTo("/api/v4/projects/9001/issues/12/notes"))
            .willReturn(okJson("{\"id\":8}")));

        OutboxEvent event = needsInfoEvent(List.of("module", "steps"));
        assertThat(handler.handle(event, 0)).isEqualTo(GitLabOutboxHandler.HandleResult.done());

        gitlab.verify(putRequestedFor(urlEqualTo("/api/v4/projects/9001/issues/12"))
            .withRequestBody(containing("repair::needs-info"))
            .withRequestBody(containing("bug"))
            .withRequestBody(containing("priority")));
        gitlab.verify(0, putRequestedFor(urlEqualTo("/api/v4/projects/9001/issues/12"))
            .withRequestBody(containing("repair::new")));
        gitlab.verify(postRequestedFor(urlEqualTo("/api/v4/projects/9001/issues/12/notes"))
            .withRequestBody(containing("loop-engine:event:"))
            .withRequestBody(containing("模块"))
            .withRequestBody(containing("复现步骤")));
    }

    @Test
    void repeatedHandlingDoesNotDuplicateNote() {
        OutboxEvent event = needsInfoEvent(List.of("module"));
        String marker = "<!-- loop-engine:event:" + event.id() + " -->";

        gitlab.stubFor(get(urlEqualTo("/api/v4/projects/9001/issues/12"))
            .willReturn(okJson("{\"labels\":[\"repair::triaging\"]}")));
        gitlab.stubFor(put(urlEqualTo("/api/v4/projects/9001/issues/12"))
            .willReturn(okJson("{}")));
        gitlab.stubFor(get(urlEqualTo("/api/v4/projects/9001/issues/12/notes?per_page=100"))
            .willReturn(okJson("[]")));
        gitlab.stubFor(post(urlEqualTo("/api/v4/projects/9001/issues/12/notes"))
            .willReturn(okJson("{\"id\":8}")));

        assertThat(handler.handle(event, 0)).isEqualTo(GitLabOutboxHandler.HandleResult.done());

        gitlab.stubFor(get(urlEqualTo("/api/v4/projects/9001/issues/12/notes?per_page=100"))
            .willReturn(okJson("""
                [{"id":8,"body":"already posted\\n%s"}]
                """.formatted(marker))));

        assertThat(handler.handle(event, 0)).isEqualTo(GitLabOutboxHandler.HandleResult.done());

        gitlab.verify(1, postRequestedFor(urlEqualTo("/api/v4/projects/9001/issues/12/notes")));
        gitlab.verify(2, getRequestedFor(urlEqualTo("/api/v4/projects/9001/issues/12/notes?per_page=100")));
    }

    @Test
    void unauthorizedBecomesTerminalFailure() {
        gitlab.stubFor(get(urlEqualTo("/api/v4/projects/9001/issues/12"))
            .willReturn(aResponse().withStatus(401).withBody("unauthorized")));

        OutboxEvent event = needsInfoEvent(List.of("module"));
        assertThat(handler.handle(event, 0))
            .isEqualTo(GitLabOutboxHandler.HandleResult.terminal("GitLab HTTP 401"));
    }

    @Test
    void serverErrorIsRescheduled() {
        gitlab.stubFor(get(urlEqualTo("/api/v4/projects/9001/issues/12"))
            .willReturn(aResponse().withStatus(500).withBody("boom")));

        OutboxEvent event = needsInfoEvent(List.of("module"));
        assertThat(handler.handle(event, 0))
            .isEqualTo(GitLabOutboxHandler.HandleResult.reschedule(Duration.ofMinutes(1)));
        assertThat(handler.handle(event, 1))
            .isEqualTo(GitLabOutboxHandler.HandleResult.reschedule(Duration.ofMinutes(5)));
    }

    @Test
    void queuedPayloadUsesProducerShapeWithoutProjectFields() {
        gitlab.stubFor(get(urlEqualTo("/api/v4/projects/9001/issues/12"))
            .willReturn(okJson("{\"labels\":[\"repair::triaging\"]}")));
        gitlab.stubFor(put(urlEqualTo("/api/v4/projects/9001/issues/12"))
            .willReturn(okJson("{}")));

        OutboxEvent event = queuedEvent();
        assertThat(handler.handle(event, 0)).isEqualTo(GitLabOutboxHandler.HandleResult.done());
        gitlab.verify(putRequestedFor(urlEqualTo("/api/v4/projects/9001/issues/12"))
            .withRequestBody(containing("repair::queued")));
    }

    /** Matches ProcessIssueDelivery.GitLabActions.forTriage incomplete payload. */
    private OutboxEvent needsInfoEvent(List<String> missingFields) {
        StringBuilder fields = new StringBuilder("[");
        for (int i = 0; i < missingFields.size(); i++) {
            if (i > 0) {
                fields.append(',');
            }
            fields.append('"').append(missingFields.get(i)).append('"');
        }
        fields.append(']');
        String payload = "{\"defectId\":\"" + knownDefectId + "\",\"missingFields\":" + fields + "}";
        return new OutboxEvent(
            UUID.randomUUID(),
            "DEFECT",
            knownDefectId.toString(),
            "GITLAB_ISSUE_NEEDS_INFO",
            payload,
            Instant.now());
    }

    /** Matches ProcessIssueDelivery.GitLabActions.forTriage complete payload. */
    private OutboxEvent queuedEvent() {
        return new OutboxEvent(
            UUID.randomUUID(),
            "DEFECT",
            knownDefectId.toString(),
            "GITLAB_ISSUE_QUEUED",
            "{\"defectId\":\"" + knownDefectId + "\"}",
            Instant.now());
    }
}
