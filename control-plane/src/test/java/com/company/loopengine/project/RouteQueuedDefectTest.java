package com.company.loopengine.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.loopengine.defect.attachment.AttachmentInspector;
import com.company.loopengine.defect.domain.DefectState;
import com.company.loopengine.gitlab.client.GitLabRepositoryReader;
import com.company.loopengine.gitlab.client.GitLabRepositoryReader.AttachmentStream;
import com.company.loopengine.project.RouteQueuedDefect.ActiveProfile;
import com.company.loopengine.project.RouteQueuedDefect.AttachmentRecord;
import com.company.loopengine.project.RouteQueuedDefect.DefectRecord;
import com.company.loopengine.project.RouteQueuedDefect.RepairTask;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class RouteQueuedDefectTest {
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private InMemoryProfiles profiles;
    private FakeGitLab gitlab;
    private InMemoryDefects defects;
    private InMemoryTasks tasks;
    private InMemoryAttachments attachments;
    private InMemoryOutbox outbox;
    private RouteQueuedDefect service;

    @BeforeEach
    void setUp() {
        profiles = new InMemoryProfiles(jsonMapper);
        gitlab = new FakeGitLab();
        defects = new InMemoryDefects();
        tasks = new InMemoryTasks();
        attachments = new InMemoryAttachments();
        outbox = new InMemoryOutbox();
        service = new RouteQueuedDefect(
            defects, profiles, tasks, attachments, outbox,
            gitlab, new AttachmentInspector(), jsonMapper);
    }

    @Test
    void createsOneTaskWithProfileBaseAndAttachmentSnapshots() {
        DefectRecord defect = queuedDefect("backend-a", "order", 7L);
        profiles.publish(profile("backend-a", "group/backend-a", "main"));
        gitlab.branchHead("group/backend-a", "main", "0123456789012345678901234567890123456789");
        gitlab.attachment("/uploads/a.png", "image/png", bytes("png"));

        service.route(defect.id());

        RepairTask task = tasks.findByDefectRevision(defect.id(), 7L).orElseThrow();
        assertThat(task.baseSha()).isEqualTo("0123456789012345678901234567890123456789");
        assertThat(task.profileSnapshot()).isEqualTo(profiles.active("backend-a").config());
        assertThat(task.profileSnapshot().path("repository").asString()).isEqualTo("group/backend-a");
        assertThat(attachments.forDefect(defect.id()).getFirst().sha256()).isEqualTo(sha256(bytes("png")));
    }

    @Test
    void twoConsumersCreateOnlyOneTask() throws Exception {
        DefectRecord defect = queuedDefect("backend-a", "order", 3L);
        profiles.publish(profile("backend-a", "group/backend-a", "main"));
        gitlab.branchHead("group/backend-a", "main", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        gitlab.attachment("/uploads/a.png", "image/png", bytes("png"));
        CyclicBarrier start = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Runnable worker = () -> {
            try {
                start.await(5, TimeUnit.SECONDS);
                service.route(defect.id());
                successes.incrementAndGet();
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            } finally {
                done.countDown();
            }
        };
        Thread t1 = new Thread(worker);
        Thread t2 = new Thread(worker);
        t1.start();
        t2.start();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(successes.get()).isEqualTo(2);
        assertThat(tasks.findByDefectRevision(defect.id(), 3L)).isPresent();
        assertThat(tasks.countForDefect(defect.id())).isEqualTo(1);
    }

    @Test
    void defectEditedDuringGitLabReadsIsRetriedFromNewRevision() {
        DefectRecord defect = queuedDefect("backend-a", "order", 1L);
        profiles.publish(profile("backend-a", "group/backend-a", "main"));
        gitlab.branchHead("group/backend-a", "main", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        gitlab.attachment("/uploads/a.png", "image/png", bytes("png"));
        AtomicReference<String> observedBase = new AtomicReference<>();
        gitlab.onBranchHead((repo, branch) -> {
            if (observedBase.compareAndSet(null, "first")) {
                defects.bumpRevision(defect.id(), completeDescription("backend-a", "order")
                    + "\n![x](/uploads/edited.png)");
                attachments.replaceReferences(defect.id(), List.of("/uploads/edited.png"));
                gitlab.attachment("/uploads/edited.png", "image/png", bytes("edited"));
            }
        });

        service.route(defect.id());

        RepairTask task = tasks.findByDefectRevision(defect.id(), 2L).orElseThrow();
        assertThat(tasks.findByDefectRevision(defect.id(), 1L)).isEmpty();
        assertThat(task.defectRevision()).isEqualTo(2L);
        assertThat(attachments.forDefect(defect.id()).getFirst().sha256())
            .isEqualTo(sha256(bytes("edited")));
    }

    @Test
    void oversizedAttachmentBlocksWithoutCreatingATask() {
        DefectRecord defect = queuedDefect("backend-a", "order", 4L);
        profiles.publish(profile("backend-a", "group/backend-a", "main"));
        gitlab.branchHead("group/backend-a", "main", "cccccccccccccccccccccccccccccccccccccccc");
        byte[] huge = new byte[20 * 1024 * 1024 + 1];
        gitlab.attachment("/uploads/a.png", "image/png", huge);

        service.route(defect.id());

        assertThat(tasks.findByDefectRevision(defect.id(), 4L)).isEmpty();
        assertThat(defects.require(defect.id()).state()).isEqualTo(DefectState.BLOCKED);
        assertThat(defects.require(defect.id()).blockReason()).isEqualTo("ATTACHMENT_TOO_LARGE");
        assertThat(outbox.comments()).extracting(InMemoryOutbox.Comment::reasonCode)
            .containsExactly("ATTACHMENT_TOO_LARGE");
    }

    private DefectRecord queuedDefect(String projectKey, String module, long revision) {
        String description = completeDescription(projectKey, module)
            + "\n![shot](/uploads/a.png)";
        DefectRecord defect = new DefectRecord(
            UUID.randomUUID(), DefectState.QUEUED, revision, description, null);
        defects.save(defect);
        attachments.replaceReferences(defect.id(), List.of("/uploads/a.png"));
        return defect;
    }

    private static String completeDescription(String projectKey, String module) {
        return """
            ## 项目标识
            %s
            ## 模块
            %s
            ## 复现步骤
            1. open
            ## 期望结果
            ok
            ## 实际结果
            500
            """.formatted(projectKey, module);
    }

    private JsonNode profile(String projectKey, String repository, String branch) {
        ObjectNode root = jsonMapper.createObjectNode();
        root.put("repository", repository);
        root.put("defaultBranch", branch);
        ArrayNode modules = root.putArray("modules");
        modules.add("order");
        root.putArray("contextPaths").add("README.md");
        ObjectNode command = root.putArray("validationCommands").addObject();
        command.put("program", "mvn");
        command.putArray("args").add("-B").add("test");
        command.put("timeoutSeconds", 600);
        root.putArray("allowedOs").add("linux");
        root.putArray("allowedNodeIds");
        root.putArray("allowedNodeOwnerIds").add("team");
        root.putObject("requiredTools").put("java", ">=21");
        root.putArray("forbiddenPaths");
        root.put("maxChangedFiles", 40);
        root.put("maxPatchBytes", 1048576);
        root.put("maxRepairRounds", 2);
        root.put("maxExternalAttempts", 2);
        root.put("retryFunctionalFailure", false);
        root.put("targetBranch", branch);
        root.put("branchPrefix", "repair/");
        root.putArray("reviewers").add("maintainers");
        root.put("projectKey", projectKey);
        return root;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static final class InMemoryProfiles implements RouteQueuedDefect.ProfileCatalog {
        private final JsonMapper jsonMapper;
        private final Map<String, ActiveProfile> byKey = new ConcurrentHashMap<>();

        InMemoryProfiles(JsonMapper jsonMapper) {
            this.jsonMapper = jsonMapper;
        }

        void publish(JsonNode config) {
            String projectKey = config.path("projectKey").asString();
            if (projectKey == null || projectKey.isBlank()) {
                projectKey = "backend-a";
            }
            ObjectNode copy = ((ObjectNode) config).deepCopy();
            copy.remove("projectKey");
            byKey.put(projectKey, new ActiveProfile(1L, copy));
        }

        ActiveProfile active(String projectKey) {
            return byKey.get(projectKey);
        }

        @Override
        public Optional<ActiveProfile> findActive(String projectKey) {
            return Optional.ofNullable(byKey.get(projectKey));
        }
    }

    static final class FakeGitLab implements GitLabRepositoryReader {
        private final Map<String, String> heads = new ConcurrentHashMap<>();
        private final Map<String, AttachmentPayload> attachments = new ConcurrentHashMap<>();
        private volatile BranchHeadListener listener;

        void branchHead(String repository, String branch, String sha) {
            heads.put(repository + "@" + branch, sha);
        }

        void attachment(String url, String contentType, byte[] body) {
            attachments.put(url, new AttachmentPayload(contentType, body));
        }

        void onBranchHead(BranchHeadListener listener) {
            this.listener = listener;
        }

        @Override
        public String resolveBranchHead(String repositoryPath, String branch) {
            BranchHeadListener current = listener;
            if (current != null) {
                current.onResolve(repositoryPath, branch);
            }
            String sha = heads.get(repositoryPath + "@" + branch);
            if (sha == null) {
                throw new IllegalStateException("unknown branch " + repositoryPath + "@" + branch);
            }
            return sha;
        }

        @Override
        public AttachmentStream openAttachment(String sourceUrl) {
            AttachmentPayload payload = attachments.get(sourceUrl);
            if (payload == null) {
                throw new IllegalStateException("unknown attachment " + sourceUrl);
            }
            return new AttachmentStream(
                payload.contentType(),
                payload.body().length,
                new ByteArrayInputStream(payload.body()));
        }

        @FunctionalInterface
        interface BranchHeadListener {
            void onResolve(String repository, String branch);
        }

        private record AttachmentPayload(String contentType, byte[] body) {}
    }

    static final class InMemoryDefects implements RouteQueuedDefect.DefectStore {
        private final Map<UUID, DefectRecord> byId = new ConcurrentHashMap<>();

        void save(DefectRecord defect) {
            byId.put(defect.id(), defect);
        }

        void bumpRevision(UUID id, String newDescription) {
            byId.computeIfPresent(id, (key, current) -> new DefectRecord(
                current.id(), current.state(), current.revision() + 1, newDescription, current.blockReason()));
        }

        DefectRecord require(UUID id) {
            return byId.get(id);
        }

        @Override
        public Optional<DefectRecord> find(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public DefectRecord lock(UUID id) {
            DefectRecord current = byId.get(id);
            if (current == null) {
                throw new IllegalStateException("missing defect " + id);
            }
            return current;
        }

        @Override
        public void markBlocked(UUID id, long expectedRevision, String reasonCode) {
            byId.computeIfPresent(id, (key, current) -> {
                if (current.revision() != expectedRevision || current.state() != DefectState.QUEUED) {
                    return current;
                }
                return new DefectRecord(
                    current.id(), DefectState.BLOCKED, current.revision(), current.description(), reasonCode);
            });
        }
    }

    static final class InMemoryTasks implements RouteQueuedDefect.TaskStore {
        private final Map<String, RepairTask> byKey = new ConcurrentHashMap<>();

        @Override
        public boolean insertIfAbsent(RepairTask task) {
            return byKey.putIfAbsent(key(task.defectId(), task.defectRevision()), task) == null;
        }

        Optional<RepairTask> findByDefectRevision(UUID defectId, long revision) {
            return Optional.ofNullable(byKey.get(key(defectId, revision)));
        }

        int countForDefect(UUID defectId) {
            return (int) byKey.keySet().stream().filter(k -> k.startsWith(defectId + ":")).count();
        }

        private static String key(UUID defectId, long revision) {
            return defectId + ":" + revision;
        }
    }

    static final class InMemoryAttachments implements RouteQueuedDefect.AttachmentStore {
        private final Map<UUID, List<AttachmentRecord>> byDefect = new ConcurrentHashMap<>();

        void replaceReferences(UUID defectId, List<String> urls) {
            List<AttachmentRecord> records = new ArrayList<>();
            for (String url : urls) {
                records.add(new AttachmentRecord(UUID.randomUUID(), defectId, url, null, null, null));
            }
            byDefect.put(defectId, records);
        }

        List<AttachmentRecord> forDefect(UUID defectId) {
            return byDefect.getOrDefault(defectId, List.of());
        }

        @Override
        public List<AttachmentRecord> listForDefect(UUID defectId) {
            return forDefect(defectId);
        }

        @Override
        public void updateMetadata(UUID attachmentId, String contentType, long sizeBytes, String sha256) {
            byDefect.values().forEach(list -> {
                for (int i = 0; i < list.size(); i++) {
                    AttachmentRecord current = list.get(i);
                    if (current.id().equals(attachmentId)) {
                        list.set(i, new AttachmentRecord(
                            current.id(),
                            current.defectId(),
                            current.sourceUrl(),
                            contentType,
                            sizeBytes,
                            sha256));
                    }
                }
            });
        }
    }

    static final class InMemoryOutbox implements RouteQueuedDefect.CommentOutbox {
        private final List<Comment> comments = new ArrayList<>();

        @Override
        public synchronized void enqueueBlockedComment(UUID defectId, String reasonCode) {
            comments.add(new Comment(defectId, reasonCode));
        }

        synchronized List<Comment> comments() {
            return List.copyOf(comments);
        }

        record Comment(UUID defectId, String reasonCode) {}
    }
}
