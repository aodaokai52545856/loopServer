package com.company.loopengine.publishing.mr;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.loopengine.gitlab.client.GitLabMergeRequestClient;
import com.company.loopengine.gitlab.client.GitLabMergeRequestClient.AmbiguousMergeRequestException;
import com.company.loopengine.gitlab.client.GitLabMergeRequestClient.CreateMergeRequestRequest;
import com.company.loopengine.gitlab.client.GitLabMergeRequestClient.RemoteMergeRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MergeRequestPublisherTest {
    private static final String TASK_ID = "0dbb4b5e-bf4c-4f18-8d20-f15a169c5c3a";
    private static final String BRANCH = "repair/backend-a/12-0dbb4b5e";
    private static final String TARGET = "main";
    private static final long PROJECT_ID = 99L;

    private FakeGitLab gitlab;
    private MergeRequestPublisher publisher;

    @BeforeEach
    void setUp() {
        gitlab = new FakeGitLab();
        publisher = new MergeRequestPublisher(gitlab);
    }

    @Test
    void findsTheCreatedMrAfterTheCreateResponseWasLost() {
        gitlab.createReturnsConnectionResetAfterPersisting();
        gitlab.searchReturns(mr(44, BRANCH, "https://gitlab/mr/44", marker(TASK_ID)));
        PublishedMergeRequest result = publisher.ensure(request(TASK_ID, BRANCH));
        assertThat(result.iid()).isEqualTo(44);
        assertThat(gitlab.createCalls()).isEqualTo(1);
    }

    @Test
    void reusesExistingMrWithMatchingMarkerWithoutCreate() {
        gitlab.searchReturns(mr(7, BRANCH, "https://gitlab/mr/7", marker(TASK_ID)));
        PublishedMergeRequest result = publisher.ensure(request(TASK_ID, BRANCH));
        assertThat(result.iid()).isEqualTo(7);
        assertThat(result.webUrl()).isEqualTo("https://gitlab/mr/7");
        assertThat(gitlab.createCalls()).isEqualTo(0);
    }

    @Test
    void createsMrWhenNoneExists() {
        PublishedMergeRequest result = publisher.ensure(request(TASK_ID, BRANCH));
        assertThat(result.iid()).isEqualTo(1);
        assertThat(gitlab.createCalls()).isEqualTo(1);
        CreateMergeRequestRequest posted = gitlab.lastCreate();
        assertThat(posted.sourceBranch()).isEqualTo(BRANCH);
        assertThat(posted.targetBranch()).isEqualTo(TARGET);
        assertThat(posted.title()).isEqualTo("fix(order): GitLab issue #12");
        assertThat(posted.description()).contains("Closes engineering/defect-intake#12");
        assertThat(posted.description()).contains("自动修复摘要：修正订单金额舍入并通过必选单元测试。");
        assertThat(posted.description()).contains(marker(TASK_ID));
        assertThat(posted.removeSourceBranch()).isTrue();
        assertThat(posted.squash()).isFalse();
        assertThat(posted.reviewerIds()).containsExactly(101L, 102L);
    }

    @Test
    void ignoresForeignMrWithoutTaskMarkerAndCreatesOwn() {
        gitlab.searchReturns(mr(9, BRANCH, "https://gitlab/mr/9", "manual MR without marker"));
        PublishedMergeRequest result = publisher.ensure(request(TASK_ID, BRANCH));
        assertThat(result.iid()).isEqualTo(1);
        assertThat(result.webUrl()).isNotEqualTo("https://gitlab/mr/9");
        assertThat(gitlab.createCalls()).isEqualTo(1);
        assertThat(gitlab.lastCreate().description()).contains(marker(TASK_ID));
    }

    @Test
    void unresolvedReviewerGroupIsWarningNotFailure() {
        gitlab.failGroupResolution("missing-reviewers");
        MergeRequestEnsureRequest req = new MergeRequestEnsureRequest(
            PROJECT_ID,
            TASK_ID,
            BRANCH,
            TARGET,
            "fix(order): GitLab issue #12",
            "engineering/defect-intake",
            12L,
            "修正订单金额舍入并通过必选单元测试。",
            List.of("backend-maintainers", "missing-reviewers"));
        PublishedMergeRequest result = publisher.ensure(req);
        assertThat(result.iid()).isEqualTo(1);
        assertThat(result.warnings()).anyMatch(w -> w.contains("missing-reviewers"));
        assertThat(gitlab.lastCreate().reviewerIds()).containsExactly(101L, 102L);
    }

    @Test
    void repeatedEnsureYieldsOneLogicalMr() {
        PublishedMergeRequest first = publisher.ensure(request(TASK_ID, BRANCH));
        gitlab.searchReturns(mr(first.iid(), BRANCH, first.webUrl(), marker(TASK_ID)));
        PublishedMergeRequest second = publisher.ensure(request(TASK_ID, BRANCH));
        assertThat(second.iid()).isEqualTo(first.iid());
        assertThat(gitlab.createCalls()).isEqualTo(1);
    }

    private static MergeRequestEnsureRequest request(String taskId, String branch) {
        return new MergeRequestEnsureRequest(
            PROJECT_ID,
            taskId,
            branch,
            TARGET,
            "fix(order): GitLab issue #12",
            "engineering/defect-intake",
            12L,
            "修正订单金额舍入并通过必选单元测试。",
            List.of("backend-maintainers"));
    }

    private static String marker(String taskId) {
        return "<!-- loop-engine:task:" + taskId + " -->";
    }

    private static RemoteMergeRequest mr(long iid, String branch, String url, String description) {
        return new RemoteMergeRequest(iid, url, branch, TARGET, description);
    }

    static final class FakeGitLab implements GitLabMergeRequestClient {
        private final List<RemoteMergeRequest> searchResults = new ArrayList<>();
        private final List<RemoteMergeRequest> afterCreateSearchResults = new ArrayList<>();
        private final Map<String, List<Long>> groups = new LinkedHashMap<>();
        private final List<String> unresolvedGroups = new ArrayList<>();
        private final AtomicInteger createCalls = new AtomicInteger();
        private boolean connectionResetAfterPersist;
        private CreateMergeRequestRequest lastCreate;
        private long nextIid = 1;

        FakeGitLab() {
            groups.put("backend-maintainers", List.of(101L, 102L));
        }

        void searchReturns(RemoteMergeRequest... mrs) {
            if (connectionResetAfterPersist) {
                afterCreateSearchResults.clear();
                afterCreateSearchResults.addAll(List.of(mrs));
            } else {
                searchResults.clear();
                searchResults.addAll(List.of(mrs));
            }
        }

        void createReturnsConnectionResetAfterPersisting() {
            connectionResetAfterPersist = true;
        }

        void failGroupResolution(String groupPath) {
            unresolvedGroups.add(groupPath);
        }

        int createCalls() {
            return createCalls.get();
        }

        CreateMergeRequestRequest lastCreate() {
            return lastCreate;
        }

        @Override
        public List<RemoteMergeRequest> searchBySourceBranch(long projectId, String sourceBranch) {
            List<RemoteMergeRequest> results = new ArrayList<>();
            for (RemoteMergeRequest mr : searchResults) {
                if (Objects.equals(mr.sourceBranch(), sourceBranch)) {
                    results.add(mr);
                }
            }
            if (createCalls.get() > 0) {
                for (RemoteMergeRequest mr : afterCreateSearchResults) {
                    if (Objects.equals(mr.sourceBranch(), sourceBranch)) {
                        results.add(mr);
                    }
                }
            }
            return List.copyOf(results);
        }

        @Override
        public RemoteMergeRequest create(long projectId, CreateMergeRequestRequest request) {
            lastCreate = request;
            createCalls.incrementAndGet();
            if (connectionResetAfterPersist) {
                throw new AmbiguousMergeRequestException("connection reset after create");
            }
            long iid = nextIid++;
            RemoteMergeRequest created = new RemoteMergeRequest(
                iid,
                "https://gitlab/mr/" + iid,
                request.sourceBranch(),
                request.targetBranch(),
                request.description());
            searchResults.add(created);
            return created;
        }

        @Override
        public List<Long> resolveReviewerGroupMembers(String groupPath) {
            if (unresolvedGroups.contains(groupPath)) {
                throw new IllegalStateException("group not found: " + groupPath);
            }
            List<Long> members = groups.get(groupPath);
            if (members == null) {
                throw new IllegalStateException("group not found: " + groupPath);
            }
            return members;
        }
    }
}
