package com.company.loopengine.publishing.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.loopengine.publishing.RobotCredentialProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BranchPublisherTest {
    private static final List<String> FORBIDDEN = List.of(".git/**", ".gitlab-ci.yml", "deploy/prod/**");
    private static final String TASK_ID = "0dbb4b5e-bf4c-4f18-8d20-f15a169c5c3a";
    private static final String ATTEMPT_ID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    private static final String PATCH_SHA = "ab".repeat(32);
    private static final long ISSUE_IID = 12L;

    @TempDir
    Path temp;

    private Path workRoot;
    private Path bareRemote;
    private String baseSha;
    private PreparePublication preparer;
    private BranchPublisher publisher;
    private Path tokenFile;

    @BeforeEach
    void setUp() throws Exception {
        workRoot = Files.createDirectories(temp.resolve("publish-work"));
        bareRemote = Files.createDirectories(temp.resolve("remote.git"));
        initBareRemoteWithMain();
        baseSha = revParse(bareRemote, "refs/heads/main");
        preparer = new PreparePublication(new GitProcess(), new PatchPolicy(), workRoot);

        tokenFile = temp.resolve("robot-token");
        Files.writeString(tokenFile, "robot-write-token-secret", StandardCharsets.UTF_8);
        RobotCredentialProvider credentials = RobotCredentialProvider.fromMountedFile(
            tokenFile,
            new RobotCredentialProvider.RobotIdentity(
                "Loop Engine Publisher",
                "loop-engine-publisher@example.invalid"));
        publisher = new BranchPublisher(new GitProcess(), credentials);
    }

    @Test
    void repeatedPublishReturnsTheExistingIdenticalCommit() {
        PublishedBranch first = publisher.publish(prepared(TASK_ID, ISSUE_IID, PATCH_SHA));
        PublishedBranch second = publisher.publish(prepared(TASK_ID, ISSUE_IID, PATCH_SHA));
        assertThat(second.branch()).isEqualTo(first.branch());
        assertThat(second.commitSha()).isEqualTo(first.commitSha());
        assertThat(remoteBranchCount("repair/backend-a/12-" + shortId(TASK_ID))).isEqualTo(1);
    }

    @Test
    void sanitizesProjectKeyInBranchName() {
        PublishedBranch published = publisher.publish(
            prepared("Backend/A!!", TASK_ID, ISSUE_IID, PATCH_SHA, "order"));
        assertThat(published.branch()).isEqualTo("repair/backend-a/12-" + shortId(TASK_ID));
        assertThat(remoteBranchCount(published.branch())).isEqualTo(1);
    }

    @Test
    void rejectsForeignExistingBranchWithoutOverwrite() throws Exception {
        String branch = "repair/backend-a/12-" + shortId(TASK_ID);
        seedForeignBranch(branch, "foreign commit without trailers");

        String before = revParse(bareRemote, "refs/heads/" + branch);
        assertThatThrownBy(() -> publisher.publish(prepared(TASK_ID, ISSUE_IID, PATCH_SHA)))
            .isInstanceOf(BranchConflictException.class)
            .hasMessageContaining("BRANCH_CONFLICT");
        assertThat(revParse(bareRemote, "refs/heads/" + branch)).isEqualTo(before);
        assertThat(remoteBranchCount(branch)).isEqualTo(1);
    }

    @Test
    void commitMessageContainsRequiredTrailers() throws Exception {
        PublishedBranch published = publisher.publish(prepared(TASK_ID, ISSUE_IID, PATCH_SHA));
        String message = run(
            bareRemote,
            "git", "log", "-1", "--format=%B", published.commitSha());
        assertThat(message).contains("fix(order): repair GitLab issue #12");
        assertThat(message).contains("Loop-Engine-Task: " + TASK_ID);
        assertThat(message).contains("Loop-Engine-Attempt: " + ATTEMPT_ID);
        assertThat(message).contains("Loop-Engine-Patch-SHA256: " + PATCH_SHA);
    }

    @Test
    void writeTokenStaysInMountedFileNotCommandArgs() throws Exception {
        String secret = Files.readString(tokenFile, StandardCharsets.UTF_8);
        PublishedBranch published = publisher.publish(prepared(TASK_ID, ISSUE_IID, PATCH_SHA));
        assertThat(published.commitSha()).isNotBlank();
        // Token must remain only in the mounted credential file for this Task surface.
        assertThat(Files.readString(tokenFile, StandardCharsets.UTF_8)).isEqualTo(secret);
    }

    private PreparedPublish prepared(String taskId, long issueIid, String patchSha) {
        return prepared("backend-a", taskId, issueIid, patchSha, "order");
    }

    private PreparedPublish prepared(
            String projectKey,
            String taskId,
            long issueIid,
            String patchSha,
            String module) {
        Publication publication = preparer.prepare(
            new PublicationRequest(
                bareRemote.toAbsolutePath().toString(),
                "main",
                baseSha,
                FORBIDDEN,
                40,
                1_048_576L,
                "unused-read-token"),
            new VerifiedPatchInput(
                newFilePatch("src/A.java", "class A {}\n").getBytes(StandardCharsets.UTF_8),
                List.of("src/A.java"),
                patchSha));
        return new PreparedPublish(
            publication,
            projectKey,
            issueIid,
            taskId,
            ATTEMPT_ID,
            module,
            patchSha);
    }

    private static String shortId(String taskId) {
        return taskId.replace("-", "").substring(0, 8);
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

    private void seedForeignBranch(String branch, String message) throws Exception {
        Path seed = Files.createDirectories(temp.resolve("foreign-" + UUID.randomUUID()));
        run(temp, "git", "clone", "--no-local", bareRemote.toAbsolutePath().toString(), seed.getFileName().toString());
        run(seed, "git", "config", "user.email", "foreign@example.com");
        run(seed, "git", "config", "user.name", "Foreign");
        run(seed, "git", "checkout", "main");
        Files.writeString(seed.resolve("src/Foreign.java"), "class Foreign {}\n");
        run(seed, "git", "add", "src/Foreign.java");
        run(seed, "git", "commit", "-m", message);
        run(seed, "git", "push", "origin", "HEAD:refs/heads/" + branch);
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

    private static String revParse(Path repo, String ref) throws Exception {
        return run(repo, "git", "rev-parse", ref).strip();
    }

    private static String newFilePatch(String path, String content) {
        String normalized = content.endsWith("\n") ? content : content + "\n";
        String[] lines = normalized.substring(0, normalized.length() - 1).split("\n", -1);
        StringBuilder body = new StringBuilder();
        body.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        body.append("new file mode 100644\n");
        body.append("--- /dev/null\n");
        body.append("+++ b/").append(path).append('\n');
        body.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String line : lines) {
            body.append('+').append(line).append('\n');
        }
        return body.toString();
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
}
