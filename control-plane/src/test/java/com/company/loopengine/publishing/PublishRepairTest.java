package com.company.loopengine.publishing;

import static com.company.loopengine.publishing.PublishRepair.COMPLETED;
import static com.company.loopengine.publishing.PublishRepair.FAILED;
import static com.company.loopengine.publishing.PublishRepair.PENDING;
import static com.company.loopengine.publishing.PublishRepair.READY_FOR_TEST;
import static com.company.loopengine.publishing.PublishRepair.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.loopengine.publishing.PublishRepair.ArtifactGate;
import com.company.loopengine.publishing.PublishRepair.AttemptStore;
import com.company.loopengine.publishing.PublishRepair.AttemptView;
import com.company.loopengine.publishing.PublishRepair.AuditStore;
import com.company.loopengine.publishing.PublishRepair.BranchGate;
import com.company.loopengine.publishing.PublishRepair.CrashInjector;
import com.company.loopengine.publishing.PublishRepair.DefectStore;
import com.company.loopengine.publishing.PublishRepair.DefectView;
import com.company.loopengine.publishing.PublishRepair.MergeRequestGate;
import com.company.loopengine.publishing.PublishRepair.OutboxEvent;
import com.company.loopengine.publishing.PublishRepair.OutboxStore;
import com.company.loopengine.publishing.PublishRepair.PreparedGate;
import com.company.loopengine.publishing.PublishRepair.PublishContext;
import com.company.loopengine.publishing.PublishRepair.PublishRecord;
import com.company.loopengine.publishing.PublishRepair.PublishStepRecord;
import com.company.loopengine.publishing.PublishRepair.PublishStepStore;
import com.company.loopengine.publishing.PublishRepair.PublishStore;
import com.company.loopengine.publishing.PublishRepair.StepCollaborators;
import com.company.loopengine.publishing.PublishRepair.StepFailedException;
import com.company.loopengine.publishing.PublishRepair.TaskStore;
import com.company.loopengine.publishing.PublishRepair.TaskView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublishRepairTest {
    private static final UUID PUBLISH_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TASK_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEFECT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ATTEMPT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID NODE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private InMemoryPublishes publishes;
    private InMemoryTasks tasks;
    private InMemoryDefects defects;
    private InMemoryAttempts attempts;
    private InMemorySteps steps;
    private InMemoryOutbox outbox;
    private InMemoryAudit audit;
    private ControllableCollaborators collaborators;
    private ControllableCrash crash;
    private PublishRepair publish;

    @BeforeEach
    void setUp() {
        publishes = new InMemoryPublishes();
        tasks = new InMemoryTasks();
        defects = new InMemoryDefects();
        attempts = new InMemoryAttempts();
        steps = new InMemorySteps();
        outbox = new InMemoryOutbox();
        audit = new InMemoryAudit();
        collaborators = new ControllableCollaborators();
        crash = new ControllableCrash();
        publish = new PublishRepair(
            publishes,
            tasks,
            defects,
            attempts,
            steps,
            outbox,
            audit,
            collaborators,
            crash,
            new ReadyForTestPolicy());
        seedHappyPath();
    }

    @Test
    void movesToReadyForTestOnlyAfterArtifactBranchCommitAndMrExist() {
        publish.run(PUBLISH_ID);
        assertThat(publishes.get(PUBLISH_ID).state()).isEqualTo(COMPLETED);
        assertThat(tasks.get(TASK_ID).state()).isEqualTo(READY_FOR_TEST);
        assertThat(defects.get(DEFECT_ID).state()).isEqualTo(READY_FOR_TEST);
        assertThat(outbox.findPending(20)).extracting(OutboxEvent::eventType)
            .contains("GITLAB_ISSUE_READY_FOR_TEST");
        String comment = outbox.readyForTestCommentPayload();
        assertThat(comment).contains("node-alpha");
        assertThat(comment).contains("src/Order.java");
        assertThat(comment).contains("abc123deadbeef");
        assertThat(comment).contains("https://gitlab/mr/44");
        assertThat(comment).doesNotContain("model prompt");
        assertThat(comment).doesNotContain("sk-");
        assertThat(comment).doesNotContain("robot-write-token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ARTIFACT_INVALID", "BASE_MOVED", "PATCH_REJECTED", "PUSH_FAILED", "MR_FAILED"})
    void noFailureCodeCanSetReadyForTest(String failureCode) {
        arrangeFailure(failureCode);
        publish.run(PUBLISH_ID);
        assertThat(defects.get(DEFECT_ID).state()).isNotEqualTo(READY_FOR_TEST);
        assertThat(publishes.get(PUBLISH_ID).state()).isEqualTo(FAILED);
        assertThat(publishes.get(PUBLISH_ID).failureCode()).isEqualTo(failureCode);
        assertThat(outbox.findPending(20)).extracting(OutboxEvent::eventType)
            .doesNotContain("GITLAB_ISSUE_READY_FOR_TEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ARTIFACT_VERIFIED",
        "PATCH_PREPARED",
        "BRANCH_PUSHED",
        "MR_CREATED",
        "STATE_FINALIZED"
    })
    void restartAfterCrashAtEachStepConvergesToOnePublication(String crashAfterStep) {
        crash.crashAfter(crashAfterStep);
        try {
            publish.run(PUBLISH_ID);
        } catch (PublishRepair.InjectedCrash ignored) {
            // simulated process death after the step boundary
        }
        crash.clear();
        publish.run(PUBLISH_ID);

        assertThat(publishes.get(PUBLISH_ID).state()).isEqualTo(COMPLETED);
        assertThat(defects.get(DEFECT_ID).state()).isEqualTo(READY_FOR_TEST);
        assertThat(collaborators.artifactCalls.get()).isEqualTo(1);
        assertThat(collaborators.prepareCalls.get()).isEqualTo(1);
        assertThat(collaborators.pushCalls.get()).isEqualTo(1);
        assertThat(collaborators.mrCalls.get()).isEqualTo(1);
        assertThat(outbox.readyForTestCount()).isEqualTo(1);
        assertThat(publishes.get(PUBLISH_ID).branchName()).isEqualTo("repair/backend-a/12-22222222");
        assertThat(publishes.get(PUBLISH_ID).commitSha()).isEqualTo("abc123deadbeef");
        assertThat(publishes.get(PUBLISH_ID).mergeRequestIid()).isEqualTo(44L);
    }

    private void seedHappyPath() {
        publishes.put(new PublishRecord(
            PUBLISH_ID, TASK_ID, ATTEMPT_ID, PENDING,
            null, null, null, null, null, null, null, null));
        tasks.put(new TaskView(TASK_ID, DEFECT_ID, NODE_ID, RUNNING));
        defects.put(new DefectView(DEFECT_ID, RUNNING));
        attempts.put(new AttemptView(ATTEMPT_ID, TASK_ID, NODE_ID, "ARTIFACT_PENDING", true));
    }

    private void arrangeFailure(String failureCode) {
        collaborators.failWith(failureCode);
    }

    private static final class InMemoryPublishes implements PublishStore {
        private final Map<UUID, PublishRecord> byId = new LinkedHashMap<>();

        void put(PublishRecord record) {
            byId.put(record.id(), record);
        }

        PublishRecord get(UUID id) {
            return byId.get(id);
        }

        @Override
        public PublishRecord find(UUID publishId) {
            return byId.get(publishId);
        }

        @Override
        public void save(PublishRecord record) {
            byId.put(record.id(), record);
        }
    }

    private static final class InMemoryTasks implements TaskStore {
        private final Map<UUID, TaskView> byId = new LinkedHashMap<>();

        void put(TaskView task) {
            byId.put(task.id(), task);
        }

        TaskView get(UUID id) {
            return byId.get(id);
        }

        @Override
        public TaskView find(UUID taskId) {
            return byId.get(taskId);
        }

        @Override
        public void save(TaskView task) {
            byId.put(task.id(), task);
        }
    }

    private static final class InMemoryDefects implements DefectStore {
        private final Map<UUID, DefectView> byId = new LinkedHashMap<>();

        void put(DefectView defect) {
            byId.put(defect.id(), defect);
        }

        DefectView get(UUID id) {
            return byId.get(id);
        }

        @Override
        public DefectView find(UUID defectId) {
            return byId.get(defectId);
        }

        @Override
        public void save(DefectView defect) {
            byId.put(defect.id(), defect);
        }

        @Override
        public void appendTransition(UUID defectId, String from, String to, String reason) {
            // captured via audit in production path; transitions asserted via state
        }
    }

    private static final class InMemoryAttempts implements AttemptStore {
        private final Map<UUID, AttemptView> byId = new LinkedHashMap<>();

        void put(AttemptView attempt) {
            byId.put(attempt.id(), attempt);
        }

        @Override
        public AttemptView find(UUID attemptId) {
            return byId.get(attemptId);
        }

        @Override
        public void save(AttemptView attempt) {
            byId.put(attempt.id(), attempt);
        }

        @Override
        public void releaseNodeSlot(UUID nodeId) {
            // observed via audit when wired; restart tests assert publish/outbox convergence
        }
    }

    private static final class InMemorySteps implements PublishStepStore {
        private final Map<String, PublishStepRecord> byKey = new LinkedHashMap<>();

        @Override
        public Optional<PublishStepRecord> find(UUID publishId, String step) {
            return Optional.ofNullable(byKey.get(publishId + ":" + step));
        }

        @Override
        public void save(PublishStepRecord step) {
            byKey.put(step.publishId() + ":" + step.step(), step);
        }

        @Override
        public List<PublishStepRecord> list(UUID publishId) {
            return byKey.values().stream()
                .filter(s -> s.publishId().equals(publishId))
                .toList();
        }
    }

    private static final class InMemoryOutbox implements OutboxStore {
        private final List<OutboxEvent> events = new ArrayList<>();

        @Override
        public void append(OutboxEvent event) {
            events.add(event);
        }

        @Override
        public List<OutboxEvent> findPending(int limit) {
            return events.stream().limit(limit).toList();
        }

        int readyForTestCount() {
            return (int) events.stream()
                .filter(e -> "GITLAB_ISSUE_READY_FOR_TEST".equals(e.eventType()))
                .count();
        }

        String readyForTestCommentPayload() {
            return events.stream()
                .filter(e -> "GITLAB_ISSUE_READY_FOR_TEST".equals(e.eventType()))
                .map(OutboxEvent::payloadJson)
                .findFirst()
                .orElse("");
        }
    }

    private static final class InMemoryAudit implements AuditStore {
        private final List<String> actions = new ArrayList<>();

        @Override
        public void append(String action, String detailJson) {
            actions.add(action + ":" + detailJson);
        }
    }

    private static final class ControllableCollaborators implements StepCollaborators {
        private final AtomicInteger artifactCalls = new AtomicInteger();
        private final AtomicInteger prepareCalls = new AtomicInteger();
        private final AtomicInteger pushCalls = new AtomicInteger();
        private final AtomicInteger mrCalls = new AtomicInteger();
        private final AtomicReference<String> failure = new AtomicReference<>();

        void failWith(String code) {
            failure.set(code);
        }

        @Override
        public ArtifactGate verifyArtifact(PublishContext context) {
            artifactCalls.incrementAndGet();
            if ("ARTIFACT_INVALID".equals(failure.get())) {
                throw new StepFailedException("ARTIFACT_INVALID", "digest mismatch");
            }
            return new ArtifactGate(
                true,
                128,
                true,
                "patch-sha",
                "artifact-sha",
                List.of("src/Order.java"),
                "all mandatory validations passed",
                "node-alpha");
        }

        @Override
        public PreparedGate preparePatch(PublishContext context, ArtifactGate artifact) {
            prepareCalls.incrementAndGet();
            if ("BASE_MOVED".equals(failure.get())) {
                throw new StepFailedException("BASE_MOVED", "target advanced");
            }
            if ("PATCH_REJECTED".equals(failure.get())) {
                throw new StepFailedException("PATCH_REJECTED", "forbidden path");
            }
            return new PreparedGate(artifact.changedFiles(), "parent-sha");
        }

        @Override
        public BranchGate pushBranch(PublishContext context, ArtifactGate artifact, PreparedGate prepared) {
            pushCalls.incrementAndGet();
            if ("PUSH_FAILED".equals(failure.get())) {
                throw new StepFailedException("PUSH_FAILED", "remote rejected");
            }
            return new BranchGate("repair/backend-a/12-22222222", "abc123deadbeef");
        }

        @Override
        public MergeRequestGate createMergeRequest(PublishContext context, BranchGate branch) {
            mrCalls.incrementAndGet();
            if ("MR_FAILED".equals(failure.get())) {
                throw new StepFailedException("MR_FAILED", "api timeout");
            }
            return new MergeRequestGate(44L, "https://gitlab/mr/44");
        }
    }

    private static final class ControllableCrash implements CrashInjector {
        private String crashAfter;

        void crashAfter(String step) {
            this.crashAfter = step;
        }

        void clear() {
            this.crashAfter = null;
        }

        @Override
        public void afterStep(String step) {
            if (Objects.equals(crashAfter, step)) {
                throw new PublishRepair.InjectedCrash("crash after " + step);
            }
        }
    }
}
