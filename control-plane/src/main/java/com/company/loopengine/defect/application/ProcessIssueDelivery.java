package com.company.loopengine.defect.application;

import com.company.loopengine.defect.domain.CompletenessResult;
import com.company.loopengine.defect.domain.DefectState;
import com.company.loopengine.defect.domain.DefectStateMachine;
import com.company.loopengine.defect.domain.IssueDescriptionParser;
import com.company.loopengine.defect.infra.DefectRepository;
import com.company.loopengine.shared.outbox.OutboxEvent;
import com.company.loopengine.shared.outbox.OutboxRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessIssueDelivery {
    private final DefectRepository defects;
    private final OutboxRepository outbox;
    private final IssueDescriptionParser parser = new IssueDescriptionParser();
    private final DefectStateMachine stateMachine = new DefectStateMachine();

    public ProcessIssueDelivery(DefectRepository defects, OutboxRepository outbox) {
        this.defects = defects;
        this.outbox = outbox;
    }

    @Transactional
    public void handle(IssueDelivery event) {
        Defect current = defects.find(event.projectId(), event.issueIid())
            .orElseGet(() -> Defect.createWithSourceTime(event, Instant.EPOCH));
        if (!event.updatedAt().isAfter(current.sourceUpdatedAt())) {
            return;
        }

        if (current.state() == DefectState.RUNNING || current.state() == DefectState.READY_FOR_TEST) {
            defects.saveSourceSnapshot(current.id(), event);
            outbox.append(GitLabActions.issueChangedDuringActiveWork(current, event));
            return;
        }
        if (current.state() == DefectState.CANCELLED) {
            return;
        }

        CompletenessResult result = parser.parse(event.description());
        DefectState target = result.complete() ? DefectState.QUEUED : DefectState.NEEDS_INFO;
        stateMachine.requireMove(current.state(), DefectState.TRIAGING);
        Defect triaging = defects.save(current.moveTo(DefectState.TRIAGING, event));
        defects.appendTransition(triaging.id(), current.state(), DefectState.TRIAGING,
            "ISSUE_UPDATED", event.eventUuid());
        stateMachine.requireMove(DefectState.TRIAGING, target);
        Defect saved = defects.save(triaging.completeTriage(result, target));
        defects.replaceReferences(saved.id(), result.facts().imageUrls(), event.updatedAt());
        defects.appendTransition(saved.id(), DefectState.TRIAGING, target,
            result.complete() ? "INFORMATION_COMPLETE" : "INFORMATION_MISSING", event.eventUuid());
        outbox.append(GitLabActions.forTriage(saved, result));
    }

    public record IssueDelivery(
        String eventUuid,
        long projectId,
        long issueIid,
        long issueGlobalId,
        String issueUrl,
        String title,
        String description,
        Instant updatedAt
    ) {}

    public record Defect(
        UUID id,
        long intakeProjectId,
        long issueIid,
        long issueGlobalId,
        String issueUrl,
        String title,
        String description,
        DefectState state,
        List<String> missingFields,
        Instant sourceUpdatedAt,
        long version
    ) {
        public static Defect createWithSourceTime(IssueDelivery event, Instant sourceUpdatedAt) {
            return new Defect(
                UUID.randomUUID(),
                event.projectId(),
                event.issueIid(),
                event.issueGlobalId(),
                event.issueUrl(),
                event.title(),
                event.description(),
                DefectState.NEW,
                List.of(),
                sourceUpdatedAt,
                0L);
        }

        public Defect moveTo(DefectState newState, IssueDelivery event) {
            return new Defect(
                id,
                intakeProjectId,
                issueIid,
                event.issueGlobalId(),
                event.issueUrl(),
                event.title(),
                event.description(),
                newState,
                missingFields,
                event.updatedAt(),
                version);
        }

        public Defect completeTriage(CompletenessResult result, DefectState target) {
            return new Defect(
                id,
                intakeProjectId,
                issueIid,
                issueGlobalId,
                issueUrl,
                title,
                description,
                target,
                List.copyOf(result.missingFields()),
                sourceUpdatedAt,
                version);
        }

        public Defect withVersion(long newVersion) {
            return new Defect(
                id,
                intakeProjectId,
                issueIid,
                issueGlobalId,
                issueUrl,
                title,
                description,
                state,
                missingFields,
                sourceUpdatedAt,
                newVersion);
        }
    }

    static final class GitLabActions {
        private GitLabActions() {}

        static OutboxEvent forTriage(Defect saved, CompletenessResult result) {
            String eventType = result.complete() ? "GITLAB_ISSUE_QUEUED" : "GITLAB_ISSUE_NEEDS_INFO";
            String payload = result.complete()
                ? "{\"defectId\":\"" + saved.id() + "\"}"
                : "{\"defectId\":\"" + saved.id() + "\",\"missingFields\":"
                    + toJsonArray(result.missingFields()) + "}";
            return new OutboxEvent(
                UUID.randomUUID(),
                "DEFECT",
                saved.id().toString(),
                eventType,
                payload,
                Instant.now());
        }

        static OutboxEvent issueChangedDuringActiveWork(Defect current, IssueDelivery event) {
            return new OutboxEvent(
                UUID.randomUUID(),
                "DEFECT",
                current.id().toString(),
                "GITLAB_ISSUE_CHANGED_DURING_ACTIVE_WORK",
                "{\"eventUuid\":\"" + event.eventUuid() + "\"}",
                Instant.now());
        }

        private static String toJsonArray(List<String> values) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(values.get(i)).append('"');
            }
            return sb.append(']').toString();
        }
    }
}
