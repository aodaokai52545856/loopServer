package com.company.loopengine.project;

import com.company.loopengine.defect.attachment.AttachmentInspector;
import com.company.loopengine.defect.attachment.AttachmentInspector.AttachmentValidationException;
import com.company.loopengine.defect.attachment.AttachmentInspector.InspectedAttachment;
import com.company.loopengine.defect.domain.DefectState;
import com.company.loopengine.defect.domain.DefectStateMachine;
import com.company.loopengine.defect.domain.IssueDescriptionParser;
import com.company.loopengine.defect.domain.IssueFacts;
import com.company.loopengine.gitlab.client.GitLabRepositoryReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Routes a QUEUED defect into an immutable repair_task snapshot.
 */
public final class RouteQueuedDefect {
    private static final int MAX_ROUTE_ATTEMPTS = 8;

    private final DefectStore defects;
    private final ProfileCatalog profiles;
    private final TaskStore tasks;
    private final AttachmentStore attachments;
    private final CommentOutbox outbox;
    private final GitLabRepositoryReader gitlab;
    private final AttachmentInspector inspector;
    private final JsonMapper jsonMapper;
    private final IssueDescriptionParser parser = new IssueDescriptionParser();
    private final DefectStateMachine stateMachine = new DefectStateMachine();

    public RouteQueuedDefect(
            DefectStore defects,
            ProfileCatalog profiles,
            TaskStore tasks,
            AttachmentStore attachments,
            CommentOutbox outbox,
            GitLabRepositoryReader gitlab,
            AttachmentInspector inspector,
            JsonMapper jsonMapper) {
        this.defects = defects;
        this.profiles = profiles;
        this.tasks = tasks;
        this.attachments = attachments;
        this.outbox = outbox;
        this.gitlab = gitlab;
        this.inspector = inspector;
        this.jsonMapper = jsonMapper;
    }

    public void route(UUID defectId) {
        for (int attempt = 0; attempt < MAX_ROUTE_ATTEMPTS; attempt++) {
            RouteAttemptResult result = attemptRoute(defectId);
            if (result != RouteAttemptResult.RETRY) {
                return;
            }
        }
        throw new IllegalStateException("Exceeded revision-change retries for defect " + defectId);
    }

    private RouteAttemptResult attemptRoute(UUID defectId) {
        DefectRecord snapshot = defects.find(defectId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown defect " + defectId));
        if (snapshot.state() != DefectState.QUEUED) {
            return RouteAttemptResult.DONE;
        }
        long expectedRevision = snapshot.revision();

        IssueFacts facts = parser.parse(snapshot.description()).facts();
        Optional<ActiveProfile> activeProfile = profiles.findActive(facts.projectKey());
        if (activeProfile.isEmpty()) {
            return block(defectId, expectedRevision, "PROFILE_NOT_FOUND");
        }
        ActiveProfile profile = activeProfile.get();
        JsonNode config = profile.config();
        if (!moduleAllowed(config, facts.module())) {
            return block(defectId, expectedRevision, "MODULE_NOT_ALLOWED");
        }

        String repository = config.path("repository").asString();
        String targetBranch = config.path("targetBranch").asString();
        String baseSha;
        try {
            baseSha = gitlab.resolveBranchHead(repository, targetBranch);
        } catch (RuntimeException ex) {
            return block(defectId, expectedRevision, "BASE_SHA_UNRESOLVED");
        }
        if (baseSha == null || !baseSha.matches("[0-9a-fA-F]{40}")) {
            return block(defectId, expectedRevision, "BASE_SHA_UNRESOLVED");
        }

        List<AttachmentRecord> referenced = attachments.listForDefect(defectId);
        List<String> urls = new ArrayList<>();
        for (AttachmentRecord attachment : referenced) {
            urls.add(attachment.sourceUrl());
        }
        // Prefer deterministic IssueFacts image URLs when present.
        if (!facts.imageUrls().isEmpty()) {
            urls = List.copyOf(facts.imageUrls());
        }

        List<InspectedAttachment> inspected;
        try {
            inspected = inspector.inspect(urls, gitlab);
        } catch (AttachmentValidationException ex) {
            return block(defectId, expectedRevision, ex.reasonCode());
        } catch (RuntimeException ex) {
            return block(defectId, expectedRevision, "ATTACHMENT_UNREADABLE");
        }

        DefectRecord locked = defects.lock(defectId);
        if (locked.state() != DefectState.QUEUED) {
            return RouteAttemptResult.DONE;
        }
        if (locked.revision() != expectedRevision) {
            return RouteAttemptResult.RETRY;
        }

        JsonNode snapshotJson = jsonMapper.readTree(jsonMapper.writeValueAsString(config));
        RepairTask task = new RepairTask(
            UUID.randomUUID(),
            defectId,
            expectedRevision,
            facts.projectKey(),
            profile.revision(),
            snapshotJson,
            baseSha.toLowerCase(),
            "QUEUED");
        tasks.insertIfAbsent(task);

        for (InspectedAttachment item : inspected) {
            AttachmentRecord match = findByUrl(referenced, item.sourceUrl())
                .orElseGet(() -> findByUrl(attachments.listForDefect(defectId), item.sourceUrl())
                    .orElse(null));
            if (match != null) {
                attachments.updateMetadata(
                    match.id(), item.contentType(), item.sizeBytes(), item.sha256());
            }
        }
        return RouteAttemptResult.DONE;
    }

    private RouteAttemptResult block(UUID defectId, long expectedRevision, String reasonCode) {
        DefectRecord locked = defects.lock(defectId);
        if (locked.state() != DefectState.QUEUED) {
            return RouteAttemptResult.DONE;
        }
        if (locked.revision() != expectedRevision) {
            return RouteAttemptResult.RETRY;
        }
        stateMachine.requireMove(DefectState.QUEUED, DefectState.BLOCKED);
        defects.markBlocked(defectId, expectedRevision, reasonCode);
        outbox.enqueueBlockedComment(defectId, reasonCode);
        return RouteAttemptResult.DONE;
    }

    private static boolean moduleAllowed(JsonNode config, String module) {
        if (module == null || module.isBlank()) {
            return false;
        }
        JsonNode modules = config.get("modules");
        if (modules == null || !modules.isArray()) {
            return false;
        }
        Iterator<JsonNode> it = modules.iterator();
        while (it.hasNext()) {
            JsonNode entry = it.next();
            if (entry.isString() && module.equals(entry.asString())) {
                return true;
            }
        }
        return false;
    }

    private static Optional<AttachmentRecord> findByUrl(List<AttachmentRecord> records, String url) {
        return records.stream().filter(r -> r.sourceUrl().equals(url)).findFirst();
    }

    private enum RouteAttemptResult {
        DONE, RETRY
    }

    public record ActiveProfile(long revision, JsonNode config) {}

    public record DefectRecord(
        UUID id,
        DefectState state,
        long revision,
        String description,
        String blockReason) {}

    public record RepairTask(
        UUID id,
        UUID defectId,
        long defectRevision,
        String projectKey,
        long profileRevision,
        JsonNode profileSnapshot,
        String baseSha,
        String state) {}

    public record AttachmentRecord(
        UUID id,
        UUID defectId,
        String sourceUrl,
        String contentType,
        Long sizeBytes,
        String sha256) {}

    public interface DefectStore {
        Optional<DefectRecord> find(UUID id);

        DefectRecord lock(UUID id);

        void markBlocked(UUID id, long expectedRevision, String reasonCode);
    }

    public interface ProfileCatalog {
        Optional<ActiveProfile> findActive(String projectKey);
    }

    public interface TaskStore {
        boolean insertIfAbsent(RepairTask task);
    }

    public interface AttachmentStore {
        List<AttachmentRecord> listForDefect(UUID defectId);

        void updateMetadata(UUID attachmentId, String contentType, long sizeBytes, String sha256);
    }

    public interface CommentOutbox {
        void enqueueBlockedComment(UUID defectId, String reasonCode);
    }
}
