package com.company.loopengine.publishing.mr;

import com.company.loopengine.gitlab.client.GitLabMergeRequestClient;
import com.company.loopengine.gitlab.client.GitLabMergeRequestClient.AmbiguousMergeRequestException;
import com.company.loopengine.gitlab.client.GitLabMergeRequestClient.CreateMergeRequestRequest;
import com.company.loopengine.gitlab.client.GitLabMergeRequestClient.RemoteMergeRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Creates or recovers a unique Merge Request keyed by source branch and hidden task marker.
 */
public final class MergeRequestPublisher {
    private final GitLabMergeRequestClient gitlab;

    public MergeRequestPublisher(GitLabMergeRequestClient gitlab) {
        this.gitlab = Objects.requireNonNull(gitlab, "gitlab");
    }

    public PublishedMergeRequest ensure(MergeRequestEnsureRequest request) {
        Objects.requireNonNull(request, "request");

        Optional<RemoteMergeRequest> existing = findMatching(request);
        if (existing.isPresent()) {
            return toPublished(existing.get(), List.of());
        }

        ReviewerResolution reviewers = resolveReviewers(request.reviewerGroups());
        CreateMergeRequestRequest createRequest = buildCreateRequest(request, reviewers.userIds());

        try {
            RemoteMergeRequest created = gitlab.create(request.targetProjectId(), createRequest);
            return toPublished(created, reviewers.warnings());
        } catch (AmbiguousMergeRequestException firstAmbiguous) {
            // Create response may have been lost after GitLab persisted the MR.
            Optional<RemoteMergeRequest> recovered = findMatching(request);
            if (recovered.isPresent()) {
                return toPublished(recovered.get(), reviewers.warnings());
            }
            // Search again before retrying POST (plan: ambiguous timeout recovery).
            try {
                RemoteMergeRequest created = gitlab.create(request.targetProjectId(), createRequest);
                return toPublished(created, reviewers.warnings());
            } catch (AmbiguousMergeRequestException secondAmbiguous) {
                Optional<RemoteMergeRequest> afterRetry = findMatching(request);
                if (afterRetry.isPresent()) {
                    return toPublished(afterRetry.get(), reviewers.warnings());
                }
                throw secondAmbiguous;
            }
        }
    }

    private Optional<RemoteMergeRequest> findMatching(MergeRequestEnsureRequest request) {
        String expectedMarker = taskMarker(request.taskId());
        List<RemoteMergeRequest> candidates =
            gitlab.searchBySourceBranch(request.targetProjectId(), request.sourceBranch());
        for (RemoteMergeRequest candidate : candidates) {
            if (!request.sourceBranch().equals(candidate.sourceBranch())) {
                continue;
            }
            if (!request.targetBranch().equals(candidate.targetBranch())) {
                continue;
            }
            String description = candidate.description() == null ? "" : candidate.description();
            if (description.contains(expectedMarker)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private ReviewerResolution resolveReviewers(List<String> reviewerGroups) {
        Set<Long> userIds = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        for (String group : reviewerGroups) {
            try {
                userIds.addAll(gitlab.resolveReviewerGroupMembers(group));
            } catch (RuntimeException ex) {
                warnings.add("unresolved reviewer group: " + group);
            }
        }
        return new ReviewerResolution(List.copyOf(userIds), List.copyOf(warnings));
    }

    private static CreateMergeRequestRequest buildCreateRequest(
            MergeRequestEnsureRequest request,
            List<Long> reviewerIds) {
        String description = "Closes " + request.closesProjectPath() + "#" + request.issueIid()
            + "\n\n"
            + "自动修复摘要：" + request.summary()
            + "\n\n"
            + taskMarker(request.taskId());
        return new CreateMergeRequestRequest(
            request.sourceBranch(),
            request.targetBranch(),
            request.title(),
            description,
            true,
            false,
            reviewerIds);
    }

    static String taskMarker(String taskId) {
        return "<!-- loop-engine:task:" + taskId + " -->";
    }

    private static PublishedMergeRequest toPublished(RemoteMergeRequest mr, List<String> warnings) {
        return new PublishedMergeRequest(mr.iid(), mr.webUrl(), warnings);
    }

    private record ReviewerResolution(List<Long> userIds, List<String> warnings) {
    }
}

record MergeRequestEnsureRequest(
        long targetProjectId,
        String taskId,
        String sourceBranch,
        String targetBranch,
        String title,
        String closesProjectPath,
        long issueIid,
        String summary,
        List<String> reviewerGroups
) {
    MergeRequestEnsureRequest {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(sourceBranch, "sourceBranch");
        Objects.requireNonNull(targetBranch, "targetBranch");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(closesProjectPath, "closesProjectPath");
        Objects.requireNonNull(summary, "summary");
        reviewerGroups = List.copyOf(Objects.requireNonNull(reviewerGroups, "reviewerGroups"));
    }
}

record PublishedMergeRequest(long iid, String webUrl, List<String> warnings) {
    PublishedMergeRequest {
        Objects.requireNonNull(webUrl, "webUrl");
        warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
    }
}
