package com.company.loopengine.publishing.git;

import com.company.loopengine.publishing.RobotCredentialProvider;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Commits a prepared repair worktree and pushes an idempotent repair branch without force.
 */
public final class BranchPublisher {
    private static final Pattern UNSAFE_PROJECT_CHARS = Pattern.compile("[^a-z0-9-]+");
    private static final Pattern MULTI_DASH = Pattern.compile("-{2,}");

    private final GitProcess git;
    private final RobotCredentialProvider credentials;

    public BranchPublisher(GitProcess git, RobotCredentialProvider credentials) {
        this.git = Objects.requireNonNull(git, "git");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    public PublishedBranch publish(PreparedPublish prepared) {
        Objects.requireNonNull(prepared, "prepared");
        String branch = branchName(prepared);
        Path workTree = prepared.publication().workTree().toAbsolutePath().normalize();

        String existingSha = resolveRemoteBranchTip(workTree, branch);
        if (existingSha != null) {
            if (trailersMatch(workTree, existingSha, prepared)) {
                return new PublishedBranch(branch, existingSha);
            }
            throw new BranchConflictException(
                "BRANCH_CONFLICT: remote branch already exists with different trailers: " + branch);
        }

        RobotCredentialProvider.RobotIdentity identity = credentials.identity();
        String message = commitMessage(prepared);
        Map<String, String> commitEnv = new LinkedHashMap<>();
        commitEnv.put("GIT_AUTHOR_NAME", identity.name());
        commitEnv.put("GIT_AUTHOR_EMAIL", identity.email());
        commitEnv.put("GIT_COMMITTER_NAME", identity.name());
        commitEnv.put("GIT_COMMITTER_EMAIL", identity.email());

        git.run(
            workTree,
            List.of("-C", workTree.toString(), "commit", "-m", message),
            commitEnv);

        String commitSha = git.run(
                workTree,
                List.of("-C", workTree.toString(), "rev-parse", "HEAD"))
            .strip();

        Path askPass = null;
        try {
            askPass = git.writeAskPass(workTree);
            Map<String, String> pushEnv = new LinkedHashMap<>();
            pushEnv.put("GIT_ASKPASS", askPass.toAbsolutePath().toString());
            pushEnv.put("GIT_TERMINAL_PROMPT", "0");
            // Write token stays in the push child environment only — never in argv or URL.
            pushEnv.put(GitProcess.ASKPASS_TOKEN_ENV, credentials.writeToken());
            git.run(
                workTree,
                List.of(
                    "-C", workTree.toString(),
                    "push", "origin",
                    "HEAD:refs/heads/" + branch),
                pushEnv);
        } finally {
            deleteQuietly(askPass);
        }

        return new PublishedBranch(branch, commitSha);
    }

    static String branchName(PreparedPublish prepared) {
        String project = sanitizeProjectKey(prepared.projectKey());
        String shortId = shortTaskId(prepared.taskId());
        return "repair/" + project + "/" + prepared.issueIid() + "-" + shortId;
    }

    static String sanitizeProjectKey(String projectKey) {
        Objects.requireNonNull(projectKey, "projectKey");
        String lower = projectKey.toLowerCase(Locale.ROOT);
        String dashed = UNSAFE_PROJECT_CHARS.matcher(lower).replaceAll("-");
        dashed = MULTI_DASH.matcher(dashed).replaceAll("-");
        while (dashed.startsWith("-")) {
            dashed = dashed.substring(1);
        }
        while (dashed.endsWith("-")) {
            dashed = dashed.substring(0, dashed.length() - 1);
        }
        if (dashed.isEmpty()) {
            throw new IllegalArgumentException("project key sanitizes to empty");
        }
        return dashed;
    }

    static String shortTaskId(String taskId) {
        Objects.requireNonNull(taskId, "taskId");
        String hex = taskId.replace("-", "").toLowerCase(Locale.ROOT);
        if (hex.length() < 8) {
            throw new IllegalArgumentException("task id too short for branch suffix");
        }
        return hex.substring(0, 8);
    }

    private static String commitMessage(PreparedPublish prepared) {
        return "fix(" + prepared.module() + "): repair GitLab issue #" + prepared.issueIid() + "\n"
            + "\n"
            + "Loop-Engine-Task: " + prepared.taskId() + "\n"
            + "Loop-Engine-Attempt: " + prepared.attemptId() + "\n"
            + "Loop-Engine-Patch-SHA256: " + prepared.patchSha256() + "\n";
    }

    private String resolveRemoteBranchTip(Path workTree, String branch) {
        Path askPass = null;
        try {
            askPass = git.writeAskPass(workTree);
            Map<String, String> env = new LinkedHashMap<>();
            env.put("GIT_ASKPASS", askPass.toAbsolutePath().toString());
            env.put("GIT_TERMINAL_PROMPT", "0");
            env.put(GitProcess.ASKPASS_TOKEN_ENV, credentials.writeToken());

            String output = git.run(
                    workTree,
                    List.of(
                        "-C", workTree.toString(),
                        "ls-remote", "--heads",
                        "origin",
                        "refs/heads/" + branch),
                    env)
                .strip();
            if (output.isEmpty()) {
                return null;
            }
            String sha = output.split("\\s+")[0].strip();
            return sha.isEmpty() ? null : sha;
        } finally {
            deleteQuietly(askPass);
        }
    }

    private boolean trailersMatch(Path workTree, String commitSha, PreparedPublish prepared) {
        Path askPass = null;
        try {
            askPass = git.writeAskPass(workTree);
            Map<String, String> env = new LinkedHashMap<>();
            env.put("GIT_ASKPASS", askPass.toAbsolutePath().toString());
            env.put("GIT_TERMINAL_PROMPT", "0");
            env.put(GitProcess.ASKPASS_TOKEN_ENV, credentials.writeToken());

            git.run(
                workTree,
                List.of(
                    "-C", workTree.toString(),
                    "fetch", "--no-tags",
                    "origin",
                    "refs/heads/" + branchName(prepared)
                        + ":refs/remotes/origin/" + branchName(prepared)),
                env);

            String body = git.run(
                    workTree,
                    List.of("-C", workTree.toString(), "log", "-1", "--format=%B", commitSha))
                .replace("\r\n", "\n");
            return body.contains("Loop-Engine-Task: " + prepared.taskId() + "\n")
                && body.contains("Loop-Engine-Attempt: " + prepared.attemptId() + "\n")
                && body.contains("Loop-Engine-Patch-SHA256: " + prepared.patchSha256() + "\n");
        } finally {
            deleteQuietly(askPass);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            java.nio.file.Files.deleteIfExists(path);
        } catch (java.io.IOException ignored) {
            // best-effort cleanup
        }
    }
}

record PreparedPublish(
        Publication publication,
        String projectKey,
        long issueIid,
        String taskId,
        String attemptId,
        String module,
        String patchSha256) {
    PreparedPublish {
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(projectKey, "projectKey");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(patchSha256, "patchSha256");
    }
}

record PublishedBranch(String branch, String commitSha) {
    PublishedBranch {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(commitSha, "commitSha");
    }
}

final class BranchConflictException extends RuntimeException {
    BranchConflictException(String message) {
        super(message);
    }
}
