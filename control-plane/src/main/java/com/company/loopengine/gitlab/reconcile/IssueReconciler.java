package com.company.loopengine.gitlab.reconcile;

import com.company.loopengine.defect.application.ProcessIssueDelivery;
import com.company.loopengine.defect.application.ProcessIssueDelivery.IssueDelivery;
import com.company.loopengine.gitlab.webhook.WebhookDeliveryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded GitLab Issue reconciliation for missed webhooks.
 * Intended schedule: every {@link #INTERVAL}.
 */
public final class IssueReconciler {
    public static final Duration INTERVAL = Duration.ofMinutes(5);
    public static final int PAGE_SIZE = 50;
    public static final int MAX_PAGES = 20;

    private final long intakeProjectId;
    private final WebhookDeliveryRepository deliveries;
    private final ProcessIssueDelivery processor;
    private final IssuePageSource source;
    private Instant cursor = Instant.EPOCH;

    public IssueReconciler(
        long intakeProjectId,
        WebhookDeliveryRepository deliveries,
        ProcessIssueDelivery processor,
        IssuePageSource source
    ) {
        this.intakeProjectId = intakeProjectId;
        this.deliveries = deliveries;
        this.processor = processor;
        this.source = source;
    }

    public Instant cursor() {
        return cursor;
    }

    public void runOnce() {
        Instant queryAfter = cursor;
        Instant highWater = cursor;
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<RemoteIssue> issues = source.listUpdatedAfter(
                intakeProjectId, queryAfter, page, PAGE_SIZE);
            if (issues.isEmpty()) {
                cursor = highWater;
                return;
            }

            for (RemoteIssue issue : issues) {
                if (hasRepairScopedLabel(issue.labels())) {
                    ingest(issue);
                }
                if (issue.updatedAt().isAfter(highWater)) {
                    highWater = issue.updatedAt();
                }
            }

            // Advance only after the full page succeeds.
            cursor = highWater;

            if (issues.size() < PAGE_SIZE) {
                return;
            }
        }
    }

    private void ingest(RemoteIssue issue) {
        String eventUuid = "reconcile:" + issue.projectId() + ":" + issue.iid() + ":" + issue.updatedAt();
        String payload = syntheticPayload(issue);
        deliveries.insert(eventUuid, "Issue Hook", payload, Instant.now());
        processor.handle(new IssueDelivery(
            eventUuid,
            issue.projectId(),
            issue.iid(),
            issue.globalId(),
            issue.webUrl(),
            issue.title(),
            issue.description(),
            issue.updatedAt()));
    }

    private static boolean hasRepairScopedLabel(List<String> labels) {
        if (labels == null) {
            return false;
        }
        for (String label : labels) {
            if (label != null && label.startsWith("repair::")) {
                return true;
            }
        }
        return false;
    }

    private static String syntheticPayload(RemoteIssue issue) {
        return "{\"object_kind\":\"issue\",\"event_type\":\"reconcile\",\"project\":{\"id\":"
            + issue.projectId()
            + "},\"object_attributes\":{\"iid\":"
            + issue.iid()
            + ",\"id\":"
            + issue.globalId()
            + ",\"title\":\""
            + escapeJson(issue.title())
            + "\",\"description\":\""
            + escapeJson(issue.description())
            + "\",\"updated_at\":\""
            + issue.updatedAt()
            + "\",\"url\":\""
            + escapeJson(issue.webUrl())
            + "\",\"labels\":"
            + toJsonArray(issue.labels())
            + "}}";
    }

    private static String toJsonArray(List<String> values) {
        List<String> safe = values == null ? List.of() : values;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < safe.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeJson(safe.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    @FunctionalInterface
    public interface IssuePageSource {
        /**
         * List issues updated after {@code updatedAfter} for the intake project.
         * {@code page} is 1-based; {@code perPage} should be {@link #PAGE_SIZE}.
         */
        List<RemoteIssue> listUpdatedAfter(
            long projectId,
            Instant updatedAfter,
            int page,
            int perPage
        );
    }

    public record RemoteIssue(
        long projectId,
        long iid,
        long globalId,
        String webUrl,
        String title,
        String description,
        Instant updatedAt,
        List<String> labels
    ) {
        public RemoteIssue {
            labels = labels == null ? List.of() : List.copyOf(new ArrayList<>(labels));
        }
    }
}
