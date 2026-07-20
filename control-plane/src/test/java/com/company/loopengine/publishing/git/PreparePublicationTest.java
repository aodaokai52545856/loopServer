package com.company.loopengine.publishing.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparePublicationTest {
    private static final List<String> FORBIDDEN = List.of(".git/**", ".gitlab-ci.yml", "deploy/prod/**");

    @TempDir
    Path temp;

    private Path workRoot;
    private Path bareRemote;
    private String baseSha;
    private PreparePublication preparer;

    @BeforeEach
    void setUp() throws Exception {
        workRoot = Files.createDirectories(temp.resolve("publish-work"));
        bareRemote = Files.createDirectories(temp.resolve("remote.git"));
        initBareRemoteWithMain();
        baseSha = revParse(bareRemote, "refs/heads/main");
        preparer = new PreparePublication(new GitProcess(), new PatchPolicy(), workRoot);
    }

    @Test
    void appliesOnlyWhenTargetBranchStillMatchesTheTaskBase() throws Exception {
        Publication publication = preparer.prepare(task(baseSha), verifiedPatch("src/A.java"));
        assertThat(publication.parentSha()).isEqualTo(baseSha);
        assertThat(publication.changedFiles()).containsExactly("src/A.java");
    }

    @Test
    void blocksPublicationWhenTargetMoved() throws Exception {
        advanceRemoteMain();
        assertThatThrownBy(() -> preparer.prepare(task(baseSha), verifiedPatch("src/A.java")))
            .isInstanceOf(BaseMovedException.class)
            .hasMessageContaining("BASE_MOVED");
    }

    @Test
    void rejectsForbiddenFileEvenWhenManifestLied() throws Exception {
        assertThatThrownBy(() -> preparer.prepare(task(baseSha), verifiedPatch(".gitlab-ci.yml")))
            .isInstanceOf(PatchPolicyException.class)
            .hasMessageContaining("FORBIDDEN_PATH");
    }

    @Test
    void rejectsMalformedPatch() throws Exception {
        VerifiedPatchInput patch = new VerifiedPatchInput(
            "not-a-valid-patch\n".getBytes(StandardCharsets.UTF_8),
            List.of("src/A.java"),
            "deadbeef".repeat(8));
        assertThatThrownBy(() -> preparer.prepare(task(baseSha), patch))
            .isInstanceOf(GitProcessException.class)
            .hasMessageContaining("GIT_APPLY_FAILED");
    }

    @Test
    void rejectsBinaryPatch() throws Exception {
        String binary = """
            diff --git a/src/data.bin b/src/data.bin
            new file mode 100644
            index 0000000..9e8e1f4
            Binary files /dev/null and b/src/data.bin differ
            """;
        VerifiedPatchInput patch = new VerifiedPatchInput(
            binary.getBytes(StandardCharsets.UTF_8),
            List.of("src/data.bin"),
            "c".repeat(64));
        assertThatThrownBy(() -> preparer.prepare(task(baseSha), patch))
            .isInstanceOf(PatchPolicyException.class)
            .hasMessageContaining("BINARY_PATCH");
    }

    @Test
    void acceptsRenameWithinPolicy() throws Exception {
        String rename = """
            diff --git a/src/Old.java b/src/New.java
            similarity index 100%
            rename from src/Old.java
            rename to src/New.java
            """;
        writeRemoteFile("src/Old.java", "class Old {}\n");
        baseSha = revParse(bareRemote, "refs/heads/main");
        Publication publication = preparer.prepare(
            task(baseSha),
            new VerifiedPatchInput(rename.getBytes(StandardCharsets.UTF_8), List.of("src/New.java"), "d".repeat(64)));
        assertThat(publication.parentSha()).isEqualTo(baseSha);
        assertThat(publication.changedFiles()).containsExactlyInAnyOrder("src/Old.java", "src/New.java");
    }

    @Test
    void rejectsPathTraversalInPatch() throws Exception {
        String traversal = """
            diff --git a/../../outside.txt b/../../outside.txt
            new file mode 100644
            --- /dev/null
            +++ b/../../outside.txt
            @@ -0,0 +1 @@
            +pwned
            """;
        VerifiedPatchInput patch = new VerifiedPatchInput(
            traversal.getBytes(StandardCharsets.UTF_8),
            List.of("../../outside.txt"),
            "e".repeat(64));
        assertThatThrownBy(() -> preparer.prepare(task(baseSha), patch))
            .isInstanceOf(PatchPolicyException.class)
            .hasMessageContaining("PATH_ESCAPE");
    }

    @Test
    void recomputesChangedFilesAndIgnoresManifestLie() throws Exception {
        String patch = newFilePatch("src/A.java", "class A {}\n");
        VerifiedPatchInput lied = new VerifiedPatchInput(
            patch.getBytes(StandardCharsets.UTF_8),
            List.of("totally/wrong.java"),
            "f".repeat(64));
        Publication publication = preparer.prepare(task(baseSha), lied);
        assertThat(publication.changedFiles()).containsExactly("src/A.java");
    }

    @Test
    void askPassScriptSuppliesTokenFromEnvWithoutEmbeddingIt() throws Exception {
        String token = "secret&token|with>meta`chars$()'\"\\;";
        Path askPass = new GitProcess().writeAskPass(workRoot);
        String scriptBody = Files.readString(askPass, StandardCharsets.UTF_8);
        assertThat(scriptBody).startsWith("#!/bin/sh");
        assertThat(scriptBody).contains(GitProcess.ASKPASS_TOKEN_ENV);
        assertThat(scriptBody).doesNotContain(token);
        assertThat(scriptBody).doesNotContain("secret");

        ProcessBuilder pb = new ProcessBuilder(resolveSh(), askPass.toAbsolutePath().toString(), "Password for https://example:");
        pb.environment().put(GitProcess.ASKPASS_TOKEN_ENV, token);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(output).isEqualTo(token + "\n");
    }

    @Test
    void prepareDeletesAskPassImmediatelyAfterFetch() throws Exception {
        Publication publication = preparer.prepare(task(baseSha), verifiedPatch("src/A.java"));
        try (var stream = Files.list(publication.workTree())) {
            assertThat(stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("askpass-") && name.endsWith(".sh");
            }).toList()).isEmpty();
        }
    }

    private PublicationRequest task(String sha) {
        return new PublicationRequest(
            bareRemote.toAbsolutePath().toString(),
            "main",
            sha,
            FORBIDDEN,
            40,
            1_048_576L,
            "unused-read-token");
    }

    private static String resolveSh() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                Path candidate = Path.of(dir, "sh.exe");
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
                candidate = Path.of(dir, "sh");
                if (Files.isRegularFile(candidate) || Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        Path gitSh = Path.of("C:\\Program Files\\Git\\bin\\sh.exe");
        if (Files.isRegularFile(gitSh)) {
            return gitSh.toString();
        }
        return "sh";
    }

    private VerifiedPatchInput verifiedPatch(String path) {
        String content = path.endsWith(".yml") || path.endsWith(".java")
            ? "changed\n"
            : "payload\n";
        String patch = newFilePatch(path, content);
        return new VerifiedPatchInput(
            patch.getBytes(StandardCharsets.UTF_8),
            List.of("manifest-said-" + path),
            "a".repeat(64));
    }

    private static String newFilePatch(String path, String content) {
        String normalized = content.endsWith("\n") ? content : content + "\n";
        String[] lines = normalized.substring(0, normalized.length() - 1).split("\n", -1);
        if (normalized.equals("\n")) {
            lines = new String[] {""};
        }
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

    private void advanceRemoteMain() throws Exception {
        writeRemoteFile("src/Moved.java", "class Moved {}\n");
    }

    private void writeRemoteFile(String relative, String content) throws Exception {
        Path seed = temp.resolve("seed-" + System.nanoTime());
        run(temp, "git", "clone", "--no-local", bareRemote.toAbsolutePath().toString(), seed.getFileName().toString());
        run(seed, "git", "config", "user.email", "publisher@example.com");
        run(seed, "git", "config", "user.name", "Publisher");
        run(seed, "git", "checkout", "main");
        Path file = seed.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        run(seed, "git", "add", relative);
        run(seed, "git", "commit", "-m", "advance");
        run(seed, "git", "push", "origin", "HEAD:refs/heads/main");
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
                "command failed (" + process.exitValue() + "): " + String.join(" ", command) + "\n" + output);
        }
        return output;
    }
}
