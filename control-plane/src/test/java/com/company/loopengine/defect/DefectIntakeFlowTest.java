package com.company.loopengine.defect;

import static com.company.loopengine.defect.domain.DefectState.NEEDS_INFO;
import static com.company.loopengine.defect.domain.DefectState.QUEUED;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.loopengine.defect.application.ProcessIssueDelivery;
import com.company.loopengine.defect.application.ProcessIssueDelivery.Defect;
import com.company.loopengine.defect.application.ProcessIssueDelivery.IssueDelivery;
import com.company.loopengine.defect.infra.DefectRepository;
import com.company.loopengine.gitlab.reconcile.IssueReconciler;
import com.company.loopengine.gitlab.webhook.WebhookDeliveryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class DefectIntakeFlowTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired ProcessIssueDelivery processor;
    @Autowired DefectRepository defects;
    @Autowired WebhookDeliveryRepository deliveries;

    private final List<IssueReconciler.RemoteIssue> gitlabIssues = new CopyOnWriteArrayList<>();
    private IssueReconciler reconciler;

    @BeforeEach
    void setUp() {
        gitlabIssues.clear();
        reconciler = new IssueReconciler(
            9001L,
            deliveries,
            processor,
            (projectId, updatedAfter, page, perPage) -> {
                if (projectId != 9001L || page != 1) {
                    return List.of();
                }
                List<IssueReconciler.RemoteIssue> pageItems = new ArrayList<>();
                for (IssueReconciler.RemoteIssue issue : gitlabIssues) {
                    if (updatedAfter == null || issue.updatedAt().isAfter(updatedAfter)) {
                        pageItems.add(issue);
                    }
                }
                return pageItems.size() > perPage ? pageItems.subList(0, perPage) : pageItems;
            });
    }

    @Test
    void reconciliationImportsAnIssueWhoseWebhookWasMissed() {
        gitlabReturnsIssue(9001, 12, "repair::new", completeDescription(), "2026-07-18T08:00:00Z");
        reconciler.runOnce();
        assertThat(defects.find(9001, 12)).get().extracting(Defect::state).isEqualTo(QUEUED);
    }

    @Test
    void validCompleteDeliveryReachesQueued() {
        processor.handle(delivery("evt-valid", 20L, completeDescription(), Instant.parse("2026-07-18T09:00:00Z")));
        assertThat(defects.find(9001, 20)).get().extracting(Defect::state).isEqualTo(QUEUED);
    }

    @Test
    void incompleteDeliveryReachesNeedsInfo() {
        processor.handle(delivery(
            "evt-incomplete",
            21L,
            "## 项目标识\napi-a\n",
            Instant.parse("2026-07-18T09:10:00Z")));
        assertThat(defects.find(9001, 21)).get().extracting(Defect::state).isEqualTo(NEEDS_INFO);
    }

    @Test
    void duplicateAndStaleDeliveriesDoNotChangeStateTwice() {
        Instant firstAt = Instant.parse("2026-07-18T10:00:00Z");
        IssueDelivery first = delivery("evt-flow-dup", 22L, completeDescription(), firstAt);
        processor.handle(first);
        int transitions = defects.countTransitions(9001, 22);

        processor.handle(first);
        processor.handle(delivery(
            "evt-flow-stale",
            22L,
            completeDescription(),
            Instant.parse("2026-07-18T09:00:00Z")));

        assertThat(defects.countTransitions(9001, 22)).isEqualTo(transitions);
        assertThat(defects.find(9001, 22)).get().extracting(Defect::state).isEqualTo(QUEUED);
    }

    @Test
    void missedIncompleteIssueReconcilesToNeedsInfo() {
        gitlabReturnsIssue(
            9001,
            23,
            "repair::new",
            "## 项目标识\napi-a\n",
            "2026-07-18T11:00:00Z");
        reconciler.runOnce();
        assertThat(defects.find(9001, 23)).get().extracting(Defect::state).isEqualTo(NEEDS_INFO);
        assertThat(deliveries.count()).isGreaterThanOrEqualTo(1);
    }

    private void gitlabReturnsIssue(
        long projectId,
        long iid,
        String repairLabel,
        String description,
        String updatedAt
    ) {
        gitlabIssues.add(new IssueReconciler.RemoteIssue(
            projectId,
            iid,
            40_000L + iid,
            "https://gitlab.example/engineering/defect-intake/-/issues/" + iid,
            "bug",
            description,
            Instant.parse(updatedAt),
            List.of(repairLabel, "bug")));
    }

    private IssueDelivery delivery(
        String eventUuid,
        long issueIid,
        String description,
        Instant updatedAt
    ) {
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

    private String completeDescription() {
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
