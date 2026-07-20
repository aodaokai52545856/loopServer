package com.company.loopengine.publishing;

import static com.company.loopengine.execution.job.RepairJobService.ARTIFACT_PENDING;
import static com.company.loopengine.execution.job.RepairJobService.RUNNING;
import static com.company.loopengine.publishing.PublishRepair.READY_FOR_TEST;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.loopengine.execution.job.RepairJobReconciler;
import com.company.loopengine.execution.job.RepairJobReconciler.NodeLiveness;
import com.company.loopengine.execution.job.RepairJobReconciler.ReconcileCandidate;
import com.company.loopengine.execution.job.RepairJobReconciler.RemoteJob;
import com.company.loopengine.execution.job.RepairJobReconciler.StaleAttemptQuery;
import com.company.loopengine.execution.job.RepairJobService;
import com.company.loopengine.execution.job.RepairJobService.Attempt;
import com.company.loopengine.execution.job.RepairJobService.AttemptStore;
import com.company.loopengine.execution.job.RepairJobService.AuditSink;
import com.company.loopengine.execution.job.RepairJobService.JobDeliveryStore;
import com.company.loopengine.execution.job.RepairJobService.JobHook;
import com.company.loopengine.execution.job.RepairJobService.Profile;
import com.company.loopengine.execution.job.RepairJobService.PublishQueue;
import com.company.loopengine.gitlab.client.GitLabMergeRequestClient.CreateMergeRequestRequest;
import com.company.loopengine.gitlab.client.GitLabMergeRequestClient.HttpGitLabMergeRequestClient;
import com.company.loopengine.gitlab.client.GitLabMergeRequestClient.RemoteMergeRequest;
import com.company.loopengine.publishing.PublishRepair.ArtifactGate;
import com.company.loopengine.publishing.PublishRepair.AttemptView;
import com.company.loopengine.publishing.PublishRepair.AuditStore;
import com.company.loopengine.publishing.PublishRepair.BranchGate;
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
import com.company.loopengine.publishing.PublishRepair.TaskStore;
import com.company.loopengine.publishing.PublishRepair.TaskView;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicationFlowTest {
    private static final long CENTRAL_PROJECT_ID = 4242L;
    private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");
    private static final String PATCH_SHA = "ab".repeat(32);
    private static final String ARTIFACT_SHA = "cd".repeat(32);

    @TempDir
    Path temp;

    private WireMockServer gitlab;
    private MutableClock clock;
    private Path bareRemote;
    private String baseSha;

    private FlowWorld world;
    private RepairJobReconciler reconciler;
    private PublishWorker publishWorker;

    @BeforeEach
    void setUp() throws Exception {
        gitlab = new WireMockServer(wireMockConfig().dynamicPort());
        gitlab.start();
        clock = new MutableClock(NOW);
        bareRemote = Files.createDirectories(temp.resolve("remote.git"));
        initBareRemoteWithMain();
        baseSha = revParse(bareRemote, "refs/heads/main");

        world = new FlowWorld(clock);
        RepairJobService jobService = RepairJobReconciler.newJobService(
            world.jobAttempts,
            world.publishQueue,
            world.deliveries,
            world.audit);
        reconciler = new RepairJobReconciler(
            world.staleQuery,
            (projectId, jobId) -> world.readRemoteJob(projectId, jobId),
            world.nodes,
            world.jobAttempts,
            jobService,
            clock);
        publishWorker = new PublishWorker(world, bareRemote, baseSha, gitlab.baseUrl());
    }

    @AfterEach
    void tearDown() {
        if (gitlab != null) {
            gitlab.stop();
        }
    }

    @Test
    void reconcilerFindsCompletedGitLabJobAndFinishesPublication() {
        Attempt attempt = runningAttemptWithSuccessfulRemoteJob();
        reconciler.runOnce();
        publishWorker.drain();
        assertThat(world.defects.get(world.defectIdOf(attempt.taskId())).state()).isEqualTo(READY_FOR_TEST);
        assertThat(countMergeRequestsFor(attempt.taskId())).isEqualTo(1);
        assertThat(remoteBranchCount("repair/backend-a/12-" + shortId(attempt.taskId()))).isEqualTo(1);
    }

    @Test
    void offlineNodeWithStillRunningJobBecomesSuspectWithoutReassign() {
        UUID taskId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID defectId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID attemptId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        UUID nodeId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        long jobId = 501L;

        world.seedRunning(attemptId, taskId, defectId, nodeId, jobId, NOW.minusSeconds(120));
        world.nodes.markOffline(nodeId);
        world.putRemoteJob(jobId, new RemoteJob(jobId, "running", null));

        reconciler.runOnce();

        assertThat(world.jobAttempts.get(attemptId).state()).isEqualTo(RepairJobReconciler.SUSPECT);
        assertThat(world.publishQueue.countFor(attemptId)).isZero();
        assertThat(world.jobAttempts.requeuedTasks()).doesNotContain(taskId);
        assertThat(world.defects.get(defectId).state()).isEqualTo(RUNNING);
    }

    @Test
    void missingJobForThirtyMinutesBlocksAsJobNotFound() {
        UUID taskId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        UUID defectId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID attemptId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID nodeId = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
        long jobId = 777L;

        Instant started = NOW.minus(Duration.ofMinutes(31));
        world.seedRunning(attemptId, taskId, defectId, nodeId, jobId, started);
        // no remote job registered → not found

        reconciler.runOnce();
        assertThat(world.jobAttempts.get(attemptId).state()).isEqualTo(RUNNING);

        clock.advance(Duration.ofMinutes(30));
        reconciler.runOnce();

        assertThat(world.jobAttempts.get(attemptId).state()).isEqualTo(RepairJobReconciler.BLOCKED);
        assertThat(world.jobAttempts.blockedReasons().get(attemptId))
            .isEqualTo(RepairJobReconciler.JOB_NOT_FOUND);
        assertThat(world.publishQueue.countFor(attemptId)).isZero();
    }

    private Attempt runningAttemptWithSuccessfulRemoteJob() {
        UUID taskId = UUID.fromString("0dbb4b5e-bf4c-4f18-8d20-f15a169c5c3a");
        UUID defectId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID attemptId = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        UUID nodeId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        long jobId = 202L;

        world.seedRunning(attemptId, taskId, defectId, nodeId, jobId, NOW.minusSeconds(90));
        world.putRemoteJob(jobId, new RemoteJob(jobId, "success", null));
        stubMergeRequestApi(taskId);
        return world.jobAttempts.get(attemptId);
    }

    private void stubMergeRequestApi(UUID taskId) {
        AtomicLong nextIid = new AtomicLong(1);
        gitlab.stubFor(get(urlPathEqualTo("/api/v4/projects/99/merge_requests"))
            .willReturn(okJson("[]")));
        gitlab.stubFor(get(urlPathEqualTo("/api/v4/groups/backend-maintainers/members"))
            .willReturn(okJson("[{\"id\":101},{\"id\":102}]")));
        gitlab.stubFor(post(urlEqualTo("/api/v4/projects/99/merge_requests"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "iid": %d,
                      "web_url": "https://gitlab.example/mr/%d",
                      "source_branch": "repair/backend-a/12-%s",
                      "target_branch": "main",
                      "description": "Closes engineering/defect-intake#12\\n\\n<!-- loop-engine:task:%s -->"
                    }
                    """.formatted(
                    nextIid.get(),
                    nextIid.getAndIncrement(),
                    shortId(taskId),
                    taskId))));
    }

    private long countMergeRequestsFor(UUID taskId) {
        return gitlab.findAll(
                com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(
                    urlEqualTo("/api/v4/projects/99/merge_requests")))
            .stream()
            .filter(r -> r.getBodyAsString() != null
                && r.getBodyAsString().contains("<!-- loop-engine:task:" + taskId + " -->"))
            .count();
    }

    private long remoteBranchCount(String branch) {
        try {
            String output = run(bareRemote, "git", "show-ref", "--heads");
            return output.lines()
                .filter(line -> line.endsWith(" refs/heads/" + branch))
                .count();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void initBareRemoteWithMain() throws Exception {
        run(bareRemote.getParent(), "git", "init", "--bare", bareRemote.toString());
        Path seed = Files.createDirectories(temp.resolve("seed"));
        run(seed, "git", "init", "-b", "main");
        run(seed, "git", "config", "user.email", "publisher@example.com");
        run(seed, "git", "config", "user.name", "Publisher");
        Files.createDirectories(seed.resolve("src"));
        Files.writeString(seed.resolve("src/Existing.java"), "class Existing {}\n");
        run(seed, "git", "add", "src/Existing.java");
        run(seed, "git", "commit", "-m", "seed");
        run(seed, "git", "remote", "add", "origin", bareRemote.toString());
        run(seed, "git", "push", "origin", "HEAD:refs/heads/main");
    }

    private static String shortId(UUID taskId) {
        return taskId.toString().replace("-", "").substring(0, 8);
    }

    private static String revParse(Path repo, String ref) throws Exception {
        return run(repo, "git", "rev-parse", ref).strip();
    }

    private static String run(Path cwd, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("timeout: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                "command failed (" + process.exitValue() + "): "
                    + String.join(" ", command) + "\n" + output);
        }
        return output;
    }

    private static final class PublishWorker {
        private final FlowWorld world;
        private final Path bareRemote;
        private final String baseSha;
        private final String gitlabBaseUrl;

        PublishWorker(FlowWorld world, Path bareRemote, String baseSha, String gitlabBaseUrl) {
            this.world = world;
            this.bareRemote = bareRemote;
            this.baseSha = baseSha;
            this.gitlabBaseUrl = gitlabBaseUrl;
        }

        void drain() {
            for (UUID publishId : List.copyOf(world.publishes.pendingIds())) {
                PublishRepair publish = new PublishRepair(
                    world.publishes,
                    world.tasks,
                    world.defects,
                    world.publishAttempts,
                    world.steps,
                    world.outbox,
                    world.publishAudit,
                    new RealGitCollaborators(world, bareRemote, baseSha, gitlabBaseUrl),
                    step -> {
                    },
                    new ReadyForTestPolicy());
                publish.run(publishId);
            }
        }
    }

    private static final class RealGitCollaborators implements StepCollaborators {
        private final Path bareRemote;
        private final String baseSha;
        private final HttpGitLabMergeRequestClient gitlab;

        RealGitCollaborators(FlowWorld world, Path bareRemote, String baseSha, String gitlabBaseUrl) {
            this.bareRemote = bareRemote;
            this.baseSha = baseSha;
            this.gitlab = new HttpGitLabMergeRequestClient(gitlabBaseUrl, "glpat-publisher");
        }

        @Override
        public ArtifactGate verifyArtifact(PublishContext context) {
            return new ArtifactGate(
                true,
                64,
                true,
                PATCH_SHA,
                ARTIFACT_SHA,
                List.of("src/Order.java"),
                "all mandatory validations passed",
                "node-alpha");
        }

        @Override
        public PreparedGate preparePatch(PublishContext context, ArtifactGate artifact) {
            return new PreparedGate(artifact.changedFiles(), baseSha);
        }

        @Override
        public BranchGate pushBranch(
                PublishContext context, ArtifactGate artifact, PreparedGate prepared) {
            try {
                Path work = Files.createDirectories(
                    bareRemote.getParent().resolve("publish-" + context.publish().id()));
                run(work.getParent(), "git", "clone", bareRemote.toAbsolutePath().toString(), work.getFileName().toString());
                run(work, "git", "config", "user.email", "loop-engine-publisher@example.invalid");
                run(work, "git", "config", "user.name", "Loop Engine Publisher");
                run(work, "git", "checkout", "main");
                Files.writeString(work.resolve("src/Order.java"), "class Order {}\n");
                run(work, "git", "add", "src/Order.java");
                String branch = "repair/backend-a/12-" + shortId(context.publish().taskId());
                String message = "fix(order): repair GitLab issue #12\n\n"
                    + "Loop-Engine-Task: " + context.publish().taskId() + "\n"
                    + "Loop-Engine-Attempt: " + context.publish().attemptId() + "\n"
                    + "Loop-Engine-Patch-SHA256: " + artifact.patchSha256() + "\n";
                run(work, "git", "commit", "-m", message);
                String sha = run(work, "git", "rev-parse", "HEAD").strip();
                run(work, "git", "push", "origin", "HEAD:refs/heads/" + branch);
                return new BranchGate(branch, sha);
            } catch (Exception ex) {
                throw new PublishRepair.StepFailedException("PUSH_FAILED", ex.getMessage());
            }
        }

        @Override
        public MergeRequestGate createMergeRequest(PublishContext context, BranchGate branch) {
            String taskId = context.publish().taskId().toString();
            String description = "Closes engineering/defect-intake#12\n\n"
                + "自动修复摘要：修正订单金额舍入并通过必选单元测试。\n\n"
                + "<!-- loop-engine:task:" + taskId + " -->";
            RemoteMergeRequest created = gitlab.create(
                99L,
                new CreateMergeRequestRequest(
                    branch.branchName(),
                    "main",
                    "fix(order): GitLab issue #12",
                    description,
                    true,
                    false,
                    List.of(101L, 102L)));
            return new MergeRequestGate(created.iid(), created.webUrl());
        }
    }

    private static final class FlowWorld {
        private final MutableClock clock;
        final InMemoryJobAttempts jobAttempts = new InMemoryJobAttempts();
        final InMemoryPublishQueue publishQueue;
        final InMemoryJobDeliveries deliveries = new InMemoryJobDeliveries();
        final InMemoryAuditSink audit = new InMemoryAuditSink();
        final InMemoryNodes nodes = new InMemoryNodes();
        final Map<Long, RemoteJob> remoteJobs = new HashMap<>();
        final InMemoryPublishes publishes = new InMemoryPublishes();
        final InMemoryTasks tasks = new InMemoryTasks();
        final InMemoryDefects defects = new InMemoryDefects();
        final InMemoryPublishAttempts publishAttempts = new InMemoryPublishAttempts();
        final InMemorySteps steps = new InMemorySteps();
        final InMemoryOutbox outbox = new InMemoryOutbox();
        final InMemoryAudit publishAudit = new InMemoryAudit();
        final StaleAttemptQuery staleQuery;

        FlowWorld(MutableClock clock) {
            this.clock = clock;
            this.publishQueue = new InMemoryPublishQueue(this);
            this.staleQuery = (olderThan, limit) -> {
                List<ReconcileCandidate> out = new ArrayList<>();
                for (Attempt attempt : jobAttempts.all()) {
                    Instant updatedAt = jobAttempts.updatedAt(attempt.id());
                    if (updatedAt == null || !updatedAt.isBefore(olderThan)) {
                        continue;
                    }
                    if (!RUNNING.equals(attempt.state())
                        && !ARTIFACT_PENDING.equals(attempt.state())
                        && !RepairJobReconciler.SUSPECT.equals(attempt.state())) {
                        continue;
                    }
                    out.add(new ReconcileCandidate(
                        attempt.id(),
                        attempt.taskId(),
                        attempt.nodeId(),
                        CENTRAL_PROJECT_ID,
                        attempt.jobId(),
                        attempt.state(),
                        updatedAt));
                    if (out.size() >= limit) {
                        break;
                    }
                }
                return out;
            };
        }

        void seedRunning(
                UUID attemptId,
                UUID taskId,
                UUID defectId,
                UUID nodeId,
                long jobId,
                Instant updatedAt) {
            Attempt attempt = new Attempt(
                attemptId, taskId, nodeId, UUID.randomUUID(), jobId, 1, RUNNING, new Profile(2, false));
            jobAttempts.put(attempt, updatedAt);
            nodes.markOnline(nodeId);
            tasks.put(new TaskView(taskId, defectId, nodeId, RUNNING));
            defects.put(new DefectView(defectId, RUNNING));
            publishAttempts.put(new AttemptView(attemptId, taskId, nodeId, RUNNING, false));
        }

        void putRemoteJob(long jobId, RemoteJob job) {
            remoteJobs.put(jobId, job);
        }

        Optional<RemoteJob> readRemoteJob(long projectId, long jobId) {
            if (projectId != CENTRAL_PROJECT_ID) {
                return Optional.empty();
            }
            return Optional.ofNullable(remoteJobs.get(jobId));
        }

        UUID defectIdOf(UUID taskId) {
            return tasks.find(taskId).defectId();
        }
    }

    private static final class InMemoryJobAttempts implements AttemptStore {
        private final Map<UUID, Attempt> byId = new LinkedHashMap<>();
        private final Map<Long, UUID> byJobId = new HashMap<>();
        private final Map<UUID, Instant> updatedAt = new HashMap<>();
        private final Map<UUID, String> blockedReasons = new HashMap<>();
        private final List<UUID> requeued = new ArrayList<>();
        private final List<UUID> finishedFailed = new ArrayList<>();

        void put(Attempt attempt, Instant at) {
            byId.put(attempt.id(), attempt);
            byJobId.put(attempt.jobId(), attempt.id());
            updatedAt.put(attempt.id(), at);
        }

        Attempt get(UUID id) {
            return byId.get(id);
        }

        Instant updatedAt(UUID id) {
            return updatedAt.get(id);
        }

        List<Attempt> all() {
            return List.copyOf(byId.values());
        }

        Map<UUID, String> blockedReasons() {
            return blockedReasons;
        }

        List<UUID> requeuedTasks() {
            return List.copyOf(requeued);
        }

        @Override
        public Attempt findByJobId(long jobId) {
            UUID id = byJobId.get(jobId);
            return id == null ? null : byId.get(id);
        }

        @Override
        public void update(Attempt attempt) {
            byId.put(attempt.id(), attempt);
            updatedAt.put(attempt.id(), Instant.now());
            if (RepairJobReconciler.BLOCKED.equals(attempt.state())) {
                blockedReasons.putIfAbsent(attempt.id(), RepairJobReconciler.JOB_NOT_FOUND);
            }
        }

        void markBlocked(UUID attemptId, String reason) {
            Attempt current = byId.get(attemptId);
            if (current == null) {
                return;
            }
            byId.put(attemptId, current.withState(RepairJobReconciler.BLOCKED));
            blockedReasons.put(attemptId, reason);
            updatedAt.put(attemptId, Instant.now());
        }

        @Override
        public void finishTaskAndDefectFailed(UUID taskId, String eventUuid, String failureReason) {
            finishedFailed.add(taskId);
        }

        @Override
        public void requeueTask(UUID taskId, UUID excludeNodeId) {
            requeued.add(taskId);
        }

        @Override
        public void releaseNodeSlot(UUID nodeId) {
            // no-op for flow test
        }
    }

    private static final class InMemoryPublishQueue implements PublishQueue {
        private final FlowWorld world;
        private final Set<UUID> queued = new HashSet<>();

        InMemoryPublishQueue(FlowWorld world) {
            this.world = world;
        }

        @Override
        public int countFor(UUID attemptId) {
            return (int) queued.stream().filter(attemptId::equals).count();
        }

        @Override
        public void enqueuePending(UUID taskId, UUID attemptId) {
            if (!queued.add(attemptId)) {
                return;
            }
            UUID publishId = UUID.nameUUIDFromBytes(("publish:" + attemptId).getBytes(StandardCharsets.UTF_8));
            world.publishes.put(new PublishRecord(
                publishId, taskId, attemptId, PublishRepair.PENDING,
                null, null, null, null, null, null, null, null));
            AttemptView attempt = world.publishAttempts.find(attemptId);
            if (attempt != null) {
                world.publishAttempts.save(new AttemptView(
                    attempt.id(), attempt.taskId(), attempt.nodeId(), ARTIFACT_PENDING, true));
            }
        }
    }

    private static final class InMemoryJobDeliveries implements JobDeliveryStore {
        private final Set<String> seen = new HashSet<>();

        @Override
        public boolean insertIfAbsent(String eventUuid, long jobId, String payloadJson) {
            return seen.add(eventUuid);
        }
    }

    private static final class InMemoryAuditSink implements AuditSink {
        @Override
        public void unknownJob(long jobId, String eventUuid) {
            // unused in happy path
        }
    }

    private static final class InMemoryNodes implements NodeLiveness {
        private final Set<UUID> online = new HashSet<>();

        void markOnline(UUID nodeId) {
            online.add(nodeId);
        }

        void markOffline(UUID nodeId) {
            online.remove(nodeId);
        }

        @Override
        public boolean isOnline(UUID nodeId) {
            return online.contains(nodeId);
        }
    }

    private static final class InMemoryPublishes implements PublishStore {
        private final Map<UUID, PublishRecord> byId = new LinkedHashMap<>();

        void put(PublishRecord record) {
            byId.put(record.id(), record);
        }

        List<UUID> pendingIds() {
            return byId.values().stream()
                .filter(r -> PublishRepair.PENDING.equals(r.state()) || PublishRepair.RUNNING.equals(r.state()))
                .map(PublishRecord::id)
                .toList();
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
            // observed via defect state
        }
    }

    private static final class InMemoryPublishAttempts implements PublishRepair.AttemptStore {
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
            // no-op
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
    }

    private static final class InMemoryAudit implements AuditStore {
        @Override
        public void append(String action, String detailJson) {
            // unused
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
