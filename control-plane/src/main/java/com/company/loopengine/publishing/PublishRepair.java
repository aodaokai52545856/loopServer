package com.company.loopengine.publishing;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Restartable Publisher orchestration: verify artifact, prepare patch, push branch, create MR,
 * then finalize ready-for-test only after every hard gate passes.
 */
public final class PublishRepair {
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String READY_FOR_TEST = "READY_FOR_TEST";
    public static final String STEP_COMPLETED = "COMPLETED";

    public static final String STEP_ARTIFACT_VERIFIED = "ARTIFACT_VERIFIED";
    public static final String STEP_PATCH_PREPARED = "PATCH_PREPARED";
    public static final String STEP_BRANCH_PUSHED = "BRANCH_PUSHED";
    public static final String STEP_MR_CREATED = "MR_CREATED";
    public static final String STEP_STATE_FINALIZED = "STATE_FINALIZED";

    private final PublishStore publishes;
    private final TaskStore tasks;
    private final DefectStore defects;
    private final AttemptStore attempts;
    private final PublishStepStore steps;
    private final OutboxStore outbox;
    private final AuditStore audit;
    private final StepCollaborators collaborators;
    private final CrashInjector crash;
    private final ReadyForTestPolicy policy;

    public PublishRepair(
            PublishStore publishes,
            TaskStore tasks,
            DefectStore defects,
            AttemptStore attempts,
            PublishStepStore steps,
            OutboxStore outbox,
            AuditStore audit,
            StepCollaborators collaborators,
            CrashInjector crash,
            ReadyForTestPolicy policy) {
        this.publishes = Objects.requireNonNull(publishes, "publishes");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.defects = Objects.requireNonNull(defects, "defects");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.collaborators = Objects.requireNonNull(collaborators, "collaborators");
        this.crash = crash == null ? step -> {
        } : crash;
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public void run(UUID publishId) {
        Objects.requireNonNull(publishId, "publishId");
        PublishRecord publish = publishes.find(publishId);
        if (publish == null) {
            throw new IllegalArgumentException("unknown publish: " + publishId);
        }
        if (COMPLETED.equals(publish.state()) || FAILED.equals(publish.state())) {
            return;
        }

        TaskView task = tasks.find(publish.taskId());
        AttemptView attempt = attempts.find(publish.attemptId());
        PublishContext context = new PublishContext(publish, task, attempt);

        try {
            ArtifactGate artifact = runArtifactVerified(context);
            PreparedGate prepared = runPatchPrepared(context, artifact);
            BranchGate branch = runBranchPushed(context, artifact, prepared);
            MergeRequestGate mr = runMrCreated(context, branch);
            runStateFinalized(context, artifact, branch, mr);
        } catch (StepFailedException ex) {
            markFailed(publishId, ex.failureCode(), ex.getMessage());
        }
    }

    private ArtifactGate runArtifactVerified(PublishContext context) {
        Optional<PublishStepRecord> existing = steps.find(context.publish().id(), STEP_ARTIFACT_VERIFIED);
        if (existing.isPresent() && STEP_COMPLETED.equals(existing.get().state())) {
            ArtifactGate restored = ArtifactGate.fromDetail(existing.get().detailJson());
            revalidateArtifact(restored);
            return restored;
        }
        ArtifactGate artifact = collaborators.verifyArtifact(context);
        revalidateArtifact(artifact);
        saveStep(context.publish().id(), STEP_ARTIFACT_VERIFIED, artifact.toDetailJson());
        PublishRecord updated = context.publish().withArtifact(artifact.artifactSha256(), artifact.patchSha256());
        publishes.save(updated);
        crash.afterStep(STEP_ARTIFACT_VERIFIED);
        return artifact;
    }

    private PreparedGate runPatchPrepared(PublishContext context, ArtifactGate artifact) {
        Optional<PublishStepRecord> existing = steps.find(context.publish().id(), STEP_PATCH_PREPARED);
        if (existing.isPresent() && STEP_COMPLETED.equals(existing.get().state())) {
            PreparedGate restored = PreparedGate.fromDetail(existing.get().detailJson());
            revalidatePrepared(restored);
            return restored;
        }
        PreparedGate prepared = collaborators.preparePatch(context, artifact);
        revalidatePrepared(prepared);
        saveStep(context.publish().id(), STEP_PATCH_PREPARED, prepared.toDetailJson());
        crash.afterStep(STEP_PATCH_PREPARED);
        return prepared;
    }

    private BranchGate runBranchPushed(
            PublishContext context, ArtifactGate artifact, PreparedGate prepared) {
        Optional<PublishStepRecord> existing = steps.find(context.publish().id(), STEP_BRANCH_PUSHED);
        if (existing.isPresent() && STEP_COMPLETED.equals(existing.get().state())) {
            BranchGate restored = BranchGate.fromDetail(existing.get().detailJson());
            revalidateBranch(restored);
            return restored;
        }
        BranchGate branch = collaborators.pushBranch(context, artifact, prepared);
        revalidateBranch(branch);
        saveStep(context.publish().id(), STEP_BRANCH_PUSHED, branch.toDetailJson());
        PublishRecord current = publishes.find(context.publish().id());
        publishes.save(current.withBranch(branch.branchName(), branch.commitSha()));
        crash.afterStep(STEP_BRANCH_PUSHED);
        return branch;
    }

    private MergeRequestGate runMrCreated(PublishContext context, BranchGate branch) {
        Optional<PublishStepRecord> existing = steps.find(context.publish().id(), STEP_MR_CREATED);
        if (existing.isPresent() && STEP_COMPLETED.equals(existing.get().state())) {
            MergeRequestGate restored = MergeRequestGate.fromDetail(existing.get().detailJson());
            revalidateMr(restored);
            return restored;
        }
        MergeRequestGate mr = collaborators.createMergeRequest(context, branch);
        revalidateMr(mr);
        saveStep(context.publish().id(), STEP_MR_CREATED, mr.toDetailJson());
        PublishRecord current = publishes.find(context.publish().id());
        publishes.save(current.withMergeRequest(mr.iid(), mr.webUrl()));
        crash.afterStep(STEP_MR_CREATED);
        return mr;
    }

    private void runStateFinalized(
            PublishContext context,
            ArtifactGate artifact,
            BranchGate branch,
            MergeRequestGate mr) {
        Optional<PublishStepRecord> existing = steps.find(context.publish().id(), STEP_STATE_FINALIZED);
        if (existing.isPresent() && STEP_COMPLETED.equals(existing.get().state())) {
            return;
        }

        PublishRecord current = publishes.find(context.publish().id());
        AttemptView attempt = attempts.find(current.attemptId());
        List<PublishStepRecord> completedSteps = steps.list(current.id());
        if (!policy.allows(artifact, branch, mr, attempt, completedSteps)) {
            throw new StepFailedException("GATE_REJECTED", "ready-for-test hard gate failed");
        }

        TaskView task = tasks.find(current.taskId());
        DefectView defect = defects.find(task.defectId());
        String fromDefect = defect.state();
        String fromTask = task.state();

        // One logical finalization transaction: publish/attempt/task/defect/outbox/audit.
        PublishRecord completed = current.withState(COMPLETED);
        publishes.save(completed);
        attempts.save(attempt.withState(COMPLETED));
        attempts.releaseNodeSlot(task.nodeId());
        tasks.save(task.withState(READY_FOR_TEST));
        defects.save(defect.withState(READY_FOR_TEST));
        defects.appendTransition(defect.id(), fromDefect, READY_FOR_TEST, "PUBLISH_COMPLETED");
        audit.append(
            "PUBLISH_READY_FOR_TEST",
            "{\"publishId\":\"" + current.id() + "\",\"taskId\":\"" + task.id() + "\"}");

        String comment = buildReadyForTestComment(artifact, branch, mr);
        outbox.append(new OutboxEvent(
            UUID.randomUUID(),
            "DEFECT",
            defect.id().toString(),
            "GITLAB_ISSUE_READY_FOR_TEST",
            comment,
            Instant.now()));

        saveStep(current.id(), STEP_STATE_FINALIZED,
            "{\"taskFrom\":\"" + fromTask + "\",\"defectFrom\":\"" + fromDefect + "\"}");
        crash.afterStep(STEP_STATE_FINALIZED);
    }

    private static void revalidateArtifact(ArtifactGate artifact) {
        if (!artifact.verified() || artifact.patchBytes() <= 0 || !artifact.mandatoryValidationsPassed()) {
            throw new StepFailedException("ARTIFACT_INVALID", "recorded artifact output failed revalidation");
        }
    }

    private static void revalidatePrepared(PreparedGate prepared) {
        if (prepared.changedFiles() == null || prepared.parentSha() == null || prepared.parentSha().isBlank()) {
            throw new StepFailedException("PATCH_REJECTED", "recorded prepare output failed revalidation");
        }
    }

    private static void revalidateBranch(BranchGate branch) {
        if (branch.branchName() == null || branch.commitSha() == null || branch.commitSha().isBlank()) {
            throw new StepFailedException("PUSH_FAILED", "recorded branch output failed revalidation");
        }
    }

    private static void revalidateMr(MergeRequestGate mr) {
        if (mr.iid() == null || mr.webUrl() == null || mr.webUrl().isBlank()) {
            throw new StepFailedException("MR_FAILED", "recorded MR output failed revalidation");
        }
    }

    private void saveStep(UUID publishId, String step, String detailJson) {
        String key = publishId + ":" + step;
        steps.save(new PublishStepRecord(
            publishId,
            step,
            STEP_COMPLETED,
            key,
            detailJson,
            Instant.now(),
            Instant.now()));
    }

    private void markFailed(UUID publishId, String failureCode, String detail) {
        PublishRecord current = publishes.find(publishId);
        if (current == null || COMPLETED.equals(current.state())) {
            return;
        }
        publishes.save(current.withFailure(failureCode, detail));
    }

    private static String buildReadyForTestComment(
            ArtifactGate artifact, BranchGate branch, MergeRequestGate mr) {
        String files = String.join(", ", artifact.changedFiles());
        return "{"
            + "\"nodeName\":\"" + escape(artifact.nodeName()) + "\","
            + "\"validationSummary\":\"" + escape(artifact.validationSummary()) + "\","
            + "\"changedFiles\":\"" + escape(files) + "\","
            + "\"commitSha\":\"" + escape(branch.commitSha()) + "\","
            + "\"mergeRequestUrl\":\"" + escape(mr.webUrl()) + "\","
            + "\"mergeRequestIid\":" + mr.iid()
            + "}";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record PublishRecord(
            UUID id,
            UUID taskId,
            UUID attemptId,
            String state,
            String artifactSha256,
            String patchSha256,
            String branchName,
            String commitSha,
            Long mergeRequestIid,
            String mergeRequestUrl,
            String failureCode,
            String failureDetail) {
        public PublishRecord withState(String newState) {
            return new PublishRecord(
                id, taskId, attemptId, newState,
                artifactSha256, patchSha256, branchName, commitSha,
                mergeRequestIid, mergeRequestUrl, failureCode, failureDetail);
        }

        public PublishRecord withArtifact(String artifactSha, String patchSha) {
            return new PublishRecord(
                id, taskId, attemptId, state,
                artifactSha, patchSha, branchName, commitSha,
                mergeRequestIid, mergeRequestUrl, failureCode, failureDetail);
        }

        public PublishRecord withBranch(String branch, String sha) {
            return new PublishRecord(
                id, taskId, attemptId, state,
                artifactSha256, patchSha256, branch, sha,
                mergeRequestIid, mergeRequestUrl, failureCode, failureDetail);
        }

        public PublishRecord withMergeRequest(long iid, String url) {
            return new PublishRecord(
                id, taskId, attemptId, state,
                artifactSha256, patchSha256, branchName, commitSha,
                iid, url, failureCode, failureDetail);
        }

        public PublishRecord withFailure(String code, String detail) {
            return new PublishRecord(
                id, taskId, attemptId, FAILED,
                artifactSha256, patchSha256, branchName, commitSha,
                mergeRequestIid, mergeRequestUrl, code, detail);
        }
    }

    public record TaskView(UUID id, UUID defectId, UUID nodeId, String state) {
        public TaskView withState(String newState) {
            return new TaskView(id, defectId, nodeId, newState);
        }
    }

    public record DefectView(UUID id, String state) {
        public DefectView withState(String newState) {
            return new DefectView(id, newState);
        }
    }

    public record AttemptView(
            UUID id,
            UUID taskId,
            UUID nodeId,
            String state,
            boolean hasTerminalSuccessEvent) {
        public AttemptView withState(String newState) {
            return new AttemptView(id, taskId, nodeId, newState, hasTerminalSuccessEvent);
        }
    }

    public record PublishStepRecord(
            UUID publishId,
            String step,
            String state,
            String idempotencyKey,
            String detailJson,
            Instant startedAt,
            Instant finishedAt) {}

    public record PublishContext(PublishRecord publish, TaskView task, AttemptView attempt) {}

    public record ArtifactGate(
            boolean verified,
            long patchBytes,
            boolean mandatoryValidationsPassed,
            String patchSha256,
            String artifactSha256,
            List<String> changedFiles,
            String validationSummary,
            String nodeName) {
        public ArtifactGate {
            changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        }

        String toDetailJson() {
            return "{"
                + "\"verified\":" + verified + ","
                + "\"patchBytes\":" + patchBytes + ","
                + "\"mandatoryValidationsPassed\":" + mandatoryValidationsPassed + ","
                + "\"patchSha256\":\"" + escape(patchSha256) + "\","
                + "\"artifactSha256\":\"" + escape(artifactSha256) + "\","
                + "\"changedFiles\":\"" + escape(String.join(",", changedFiles)) + "\","
                + "\"validationSummary\":\"" + escape(validationSummary) + "\","
                + "\"nodeName\":\"" + escape(nodeName) + "\""
                + "}";
        }

        static ArtifactGate fromDetail(String detailJson) {
            return new ArtifactGate(
                boolField(detailJson, "verified"),
                longField(detailJson, "patchBytes"),
                boolField(detailJson, "mandatoryValidationsPassed"),
                stringField(detailJson, "patchSha256"),
                stringField(detailJson, "artifactSha256"),
                List.of(stringField(detailJson, "changedFiles").split(",")),
                stringField(detailJson, "validationSummary"),
                stringField(detailJson, "nodeName"));
        }
    }

    public record PreparedGate(List<String> changedFiles, String parentSha) {
        public PreparedGate {
            changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        }

        String toDetailJson() {
            return "{"
                + "\"changedFiles\":\"" + escape(String.join(",", changedFiles)) + "\","
                + "\"parentSha\":\"" + escape(parentSha) + "\""
                + "}";
        }

        static PreparedGate fromDetail(String detailJson) {
            String files = stringField(detailJson, "changedFiles");
            return new PreparedGate(
                files.isBlank() ? List.of() : List.of(files.split(",")),
                stringField(detailJson, "parentSha"));
        }
    }

    public record BranchGate(String branchName, String commitSha) {
        String toDetailJson() {
            return "{"
                + "\"branchName\":\"" + escape(branchName) + "\","
                + "\"commitSha\":\"" + escape(commitSha) + "\""
                + "}";
        }

        static BranchGate fromDetail(String detailJson) {
            return new BranchGate(
                stringField(detailJson, "branchName"),
                stringField(detailJson, "commitSha"));
        }
    }

    public record MergeRequestGate(Long iid, String webUrl) {
        String toDetailJson() {
            return "{"
                + "\"iid\":" + iid + ","
                + "\"webUrl\":\"" + escape(webUrl) + "\""
                + "}";
        }

        static MergeRequestGate fromDetail(String detailJson) {
            return new MergeRequestGate(
                longField(detailJson, "iid"),
                stringField(detailJson, "webUrl"));
        }
    }

    public record OutboxEvent(
            UUID id,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payloadJson,
            Instant occurredAt) {}

    public interface PublishStore {
        PublishRecord find(UUID publishId);

        void save(PublishRecord record);
    }

    public interface TaskStore {
        TaskView find(UUID taskId);

        void save(TaskView task);
    }

    public interface DefectStore {
        DefectView find(UUID defectId);

        void save(DefectView defect);

        void appendTransition(UUID defectId, String from, String to, String reason);
    }

    public interface AttemptStore {
        AttemptView find(UUID attemptId);

        void save(AttemptView attempt);

        void releaseNodeSlot(UUID nodeId);
    }

    public interface PublishStepStore {
        Optional<PublishStepRecord> find(UUID publishId, String step);

        void save(PublishStepRecord step);

        List<PublishStepRecord> list(UUID publishId);
    }

    public interface OutboxStore {
        void append(OutboxEvent event);

        List<OutboxEvent> findPending(int limit);
    }

    public interface AuditStore {
        void append(String action, String detailJson);
    }

    public interface StepCollaborators {
        ArtifactGate verifyArtifact(PublishContext context);

        PreparedGate preparePatch(PublishContext context, ArtifactGate artifact);

        BranchGate pushBranch(PublishContext context, ArtifactGate artifact, PreparedGate prepared);

        MergeRequestGate createMergeRequest(PublishContext context, BranchGate branch);
    }

    public interface CrashInjector {
        void afterStep(String step);
    }

    public static final class StepFailedException extends RuntimeException {
        private final String failureCode;

        public StepFailedException(String failureCode, String message) {
            super(message);
            this.failureCode = Objects.requireNonNull(failureCode, "failureCode");
        }

        public String failureCode() {
            return failureCode;
        }
    }

    public static final class InjectedCrash extends RuntimeException {
        public InjectedCrash(String message) {
            super(message);
        }
    }

    private static boolean boolField(String json, String name) {
        String token = "\"" + name + "\":";
        int idx = json.indexOf(token);
        if (idx < 0) {
            return false;
        }
        int start = idx + token.length();
        return json.regionMatches(start, "true", 0, 4);
    }

    private static long longField(String json, String name) {
        String token = "\"" + name + "\":";
        int idx = json.indexOf(token);
        if (idx < 0) {
            return 0L;
        }
        int start = idx + token.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (start == end) {
            return 0L;
        }
        return Long.parseLong(json.substring(start, end));
    }

    private static String stringField(String json, String name) {
        String token = "\"" + name + "\":\"";
        int idx = json.indexOf(token);
        if (idx < 0) {
            return "";
        }
        int start = idx + token.length();
        StringBuilder out = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                out.append(json.charAt(++i));
                continue;
            }
            if (c == '"') {
                break;
            }
            out.append(c);
        }
        return out.toString();
    }
}
