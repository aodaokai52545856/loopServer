package com.company.loopengine.defect.application;

import static com.company.loopengine.defect.domain.DefectState.NEEDS_INFO;
import static com.company.loopengine.defect.domain.DefectState.QUEUED;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.loopengine.defect.application.ProcessIssueDelivery.Defect;
import com.company.loopengine.defect.application.ProcessIssueDelivery.IssueDelivery;
import com.company.loopengine.defect.infra.DefectRepository;
import com.company.loopengine.shared.outbox.OutboxEvent;
import com.company.loopengine.shared.outbox.OutboxRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class ProcessIssueDeliveryTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired ProcessIssueDelivery useCase;
    @Autowired DefectRepository defects;
    @Autowired OutboxRepository outbox;

    @Test
    void incompleteIssueBecomesNeedsInfoAndRequestsExactFields() {
        useCase.handle(delivery("evt-7", 12L, issue("## 项目标识\napi-a")));

        Defect defect = defects.find(9001, 12).orElseThrow();
        assertThat(defect.state()).isEqualTo(NEEDS_INFO);
        assertThat(defect.missingFields()).containsExactly("module", "steps", "expected", "actual");
        assertThat(outbox.findPending(10)).extracting(OutboxEvent::eventType)
            .contains("GITLAB_ISSUE_NEEDS_INFO");
    }

    @Test
    void duplicateAndStaleDeliveriesDoNotAddTransitions() {
        IssueDelivery first = delivery(
            "evt-dup", 13L, issue("## 项目标识\napi-a"), Instant.parse("2026-07-19T10:00:00Z"));
        useCase.handle(first);
        int afterFirst = defects.countTransitions(9001, 13);

        useCase.handle(first);
        useCase.handle(delivery(
            "evt-stale", 13L, issue("## 项目标识\napi-a"), Instant.parse("2026-07-19T09:00:00Z")));

        assertThat(defects.countTransitions(9001, 13)).isEqualTo(afterFirst);
        assertThat(defects.find(9001, 13).orElseThrow().state()).isEqualTo(NEEDS_INFO);
    }

    @Test
    void completeIssueEndsAtQueued() {
        useCase.handle(delivery("evt-complete", 14L, completeIssue(), Instant.parse("2026-07-19T11:00:00Z")));

        Defect defect = defects.find(9001, 14).orElseThrow();
        assertThat(defect.state()).isEqualTo(QUEUED);
        assertThat(defect.missingFields()).isEmpty();
        assertThat(outbox.findPending(10)).extracting(OutboxEvent::eventType)
            .contains("GITLAB_ISSUE_QUEUED");
    }

    private IssueDelivery delivery(String eventUuid, long issueIid, String description) {
        return delivery(eventUuid, issueIid, description, Instant.parse("2026-07-19T10:00:00Z"));
    }

    private IssueDelivery delivery(
        String eventUuid, long issueIid, String description, Instant updatedAt) {
        return new IssueDelivery(
            eventUuid,
            9001L,
            issueIid,
            40L + issueIid,
            "https://gitlab.example/engineering/defect-intake/-/issues/" + issueIid,
            "bug",
            description,
            updatedAt);
    }

    private String issue(String body) {
        return body;
    }

    private String completeIssue() {
        return """
            ## 项目标识
            api-a
            ## 模块
            order
            ## 复现步骤
            1. open
            ## 期望结果
            ok
            ## 实际结果
            500
            """;
    }
}
