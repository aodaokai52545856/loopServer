package com.company.loopengine.gitlab.outbox;

import com.company.loopengine.gitlab.client.GitLabClient;
import com.company.loopengine.gitlab.client.GitLabClient.GitLabRetryableException;
import com.company.loopengine.gitlab.client.GitLabClient.GitLabTerminalException;
import com.company.loopengine.gitlab.client.HttpGitLabClient;
import com.company.loopengine.shared.outbox.OutboxEvent;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GitLabOutboxHandler {
    private static final Duration[] BACKOFF = {
        Duration.ofMinutes(1),
        Duration.ofMinutes(5),
        Duration.ofMinutes(30),
        Duration.ofHours(2),
        Duration.ofHours(8)
    };
    private static final Map<String, String> FIELD_LABELS = new LinkedHashMap<>();
    private static final Pattern DEFECT_ID = Pattern.compile("\"defectId\"\\s*:\\s*\"([^\"]+)\"");

    static {
        FIELD_LABELS.put("projectKey", "项目标识");
        FIELD_LABELS.put("module", "模块");
        FIELD_LABELS.put("steps", "复现步骤");
        FIELD_LABELS.put("expected", "期望结果");
        FIELD_LABELS.put("actual", "实际结果");
    }

    private final GitLabClient gitLab;
    private final DefectIssueLocator defects;

    public GitLabOutboxHandler(GitLabClient gitLab, DefectIssueLocator defects) {
        this.gitLab = gitLab;
        this.defects = defects;
    }

    public HandleResult handle(OutboxEvent event, int attemptCount) {
        try {
            return switch (event.eventType()) {
                case "GITLAB_ISSUE_NEEDS_INFO" -> handleNeedsInfo(event);
                case "GITLAB_ISSUE_QUEUED" -> handleQueued(event);
                default -> HandleResult.done();
            };
        } catch (GitLabTerminalException ex) {
            return HandleResult.terminal(ex.getMessage());
        } catch (GitLabRetryableException ex) {
            return HandleResult.reschedule(backoffFor(attemptCount));
        }
    }

    private HandleResult handleNeedsInfo(OutboxEvent event) {
        UUID defectId = resolveDefectId(event);
        DefectIssueLocator.IssueRef issue = locateIssue(defectId);
        List<String> missing = HttpGitLabClient.readStringArrayField(event.payloadJson(), "missingFields");
        String marker = "<!-- loop-engine:event:" + event.id() + " -->";
        gitLab.updateRepairLabel(issue.projectId(), issue.issueIid(), "repair::needs-info");
        gitLab.ensureNote(
            issue.projectId(),
            issue.issueIid(),
            marker,
            needsInfoMarkdown(missing, marker));
        return HandleResult.done();
    }

    private HandleResult handleQueued(OutboxEvent event) {
        UUID defectId = resolveDefectId(event);
        DefectIssueLocator.IssueRef issue = locateIssue(defectId);
        gitLab.updateRepairLabel(issue.projectId(), issue.issueIid(), "repair::queued");
        return HandleResult.done();
    }

    private DefectIssueLocator.IssueRef locateIssue(UUID defectId) {
        return defects.locate(defectId).orElseThrow(() ->
            new GitLabTerminalException("Unknown defectId: " + defectId));
    }

    private static UUID resolveDefectId(OutboxEvent event) {
        Matcher matcher = DEFECT_ID.matcher(event.payloadJson() == null ? "" : event.payloadJson());
        String raw = matcher.find() ? matcher.group(1) : event.aggregateId();
        try {
            return UUID.fromString(raw);
        } catch (RuntimeException ex) {
            throw new GitLabTerminalException("Invalid defectId: " + raw);
        }
    }

    private static String needsInfoMarkdown(List<String> missingFields, String marker) {
        StringBuilder body = new StringBuilder("自动处理尚缺少以下信息：\n");
        for (String field : missingFields) {
            body.append("- ").append(FIELD_LABELS.getOrDefault(field, field)).append('\n');
        }
        body.append('\n')
            .append("请编辑 Issue 描述并保留标准二级标题；更新后系统会重新检查。\n")
            .append(marker);
        return body.toString();
    }

    private static Duration backoffFor(int attemptCount) {
        int index = Math.min(Math.max(attemptCount, 0), BACKOFF.length - 1);
        return BACKOFF[index];
    }

    @FunctionalInterface
    public interface DefectIssueLocator {
        Optional<IssueRef> locate(UUID defectId);

        record IssueRef(long projectId, long issueIid) {}
    }

    public record HandleResult(Status status, Duration retryAfter, String error) {
        public enum Status { DONE, RESCHEDULE, TERMINAL }

        public static HandleResult done() {
            return new HandleResult(Status.DONE, null, null);
        }

        public static HandleResult reschedule(Duration retryAfter) {
            return new HandleResult(Status.RESCHEDULE, retryAfter, null);
        }

        public static HandleResult terminal(String error) {
            return new HandleResult(Status.TERMINAL, null, error);
        }
    }
}
