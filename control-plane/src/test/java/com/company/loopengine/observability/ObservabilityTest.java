package com.company.loopengine.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ObservabilityTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    LoopMetrics loopMetrics;

    @Test
    void exposesCoreMetricsWithoutSecretValues() throws Exception {
        loopMetrics.setTasksCurrent("queued", "backend-a", 1);
        String scrape = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
        assertThat(scrape)
                .contains("loop_tasks_current{project_key=\"backend-a\",state=\"queued\"")
                .contains("loop_defects_current")
                .contains("loop_nodes")
                .contains("loop_outbox_events_total")
                .doesNotContain("glpat-")
                .doesNotContain("OPENAI_API_KEY");
    }

    @Test
    void registersLowCardinalityMetricFamilies() {
        assertThat(loopMetrics.metricNames())
                .containsExactlyInAnyOrderElementsOf(LoopMetrics.METRIC_NAMES);
    }

    @Test
    void redactsBareGitlabTokenBodiesNotOnlyPrefixes() {
        assertThat(SecretRedactingConverter.redact("bare glpat-ABCDEF123456 leaked"))
                .doesNotContain("glpat-")
                .doesNotContain("ABCDEF123456")
                .contains("***");
        assertThat(SecretRedactingConverter.redact("gldt-xyz789 ghp_abcdefghijklmnopqrst"))
                .doesNotContain("gldt-xyz789")
                .doesNotContain("ghp_abcdefghijklmnopqrst")
                .contains("***");
    }

    @Test
    void redactsSecretValuesInMessagesAndJsonLayout() {
        String raw = "event=auth_probe api_key=sk-secret gitlab_token=glpat-ABCDEF123456"
                + " password=hunter2 OPENAI_API_KEY=should-not-appear private_key=abc";
        String redacted = SecretRedactingConverter.redact(raw);
        assertThat(redacted)
                .doesNotContain("glpat-ABCDEF123456")
                .doesNotContain("ABCDEF123456")
                .doesNotContain("sk-secret")
                .doesNotContain("hunter2")
                .doesNotContain("should-not-appear")
                .doesNotContain("private_key=abc")
                .contains("***");

        MDC.put("requestId", "req-1");
        MDC.put("taskId", "task-1");
        MDC.put("attemptId", "attempt-1");
        MDC.put("nodeId", "node-1");
        MDC.put("event", "auth_probe");
        MDC.put("errorCode", "TOKEN_REJECTED");
        try {
            JsonStructuredLayout layout = new JsonStructuredLayout();
            layout.setServiceName("loop-engine-control-plane");
            ch.qos.logback.classic.spi.LoggingEvent event = new ch.qos.logback.classic.spi.LoggingEvent();
            event.setTimeStamp(System.currentTimeMillis());
            event.setLevel(ch.qos.logback.classic.Level.INFO);
            event.setLoggerName("observability-test");
            event.setMessage(raw);
            event.setMDCPropertyMap(MDC.getCopyOfContextMap());
            String json = layout.doLayout(event);
            assertThat(json)
                    .contains("\"timestamp\"")
                    .contains("\"level\":\"INFO\"")
                    .contains("\"service\":\"loop-engine-control-plane\"")
                    .contains("\"requestId\":\"req-1\"")
                    .contains("\"taskId\":\"task-1\"")
                    .contains("\"attemptId\":\"attempt-1\"")
                    .contains("\"nodeId\":\"node-1\"")
                    .contains("\"event\":\"auth_probe\"")
                    .contains("\"errorCode\":\"TOKEN_REJECTED\"")
                    .doesNotContain("glpat-ABCDEF123456")
                    .doesNotContain("OPENAI_API_KEY=should-not-appear");
        } finally {
            MDC.clear();
        }
    }
}
