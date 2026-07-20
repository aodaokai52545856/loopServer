package com.company.loopengine.publishing.git;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Clones a clean temporary repository, verifies the target Base SHA, applies a patch, and
 * recomputes changed-file policy without trusting the Artifact manifest.
 */
public final class PreparePublication {
    private final GitProcess git;
    private final PatchPolicy policy;
    private final Path workRoot;

    public PreparePublication(GitProcess git, PatchPolicy policy, Path workRoot) {
        this.git = Objects.requireNonNull(git, "git");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.workRoot = Objects.requireNonNull(workRoot, "workRoot").toAbsolutePath().normalize();
    }

    public PreparePublication() {
        this(
            new GitProcess(),
            new PatchPolicy(),
            resolveWorkRoot());
    }

    public Publication prepare(PublicationRequest request, VerifiedPatchInput patch) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(patch, "patch");
        policy.validatePatchBytes(patch.patchBytes(), request.maxPatchBytes());
        policy.rejectUnsafePatchPaths(patch.patchBytes());

        Path repoDir = createUniqueWorkDir();
        Path askPass = null;
        Path patchFile = null;
        try {
            git.run(repoDir, List.of("init", repoDir.toString()));
            git.run(repoDir, List.of("-C", repoDir.toString(), "remote", "add", "origin", request.targetUrl()));

            askPass = git.writeAskPass(repoDir);
            Map<String, String> fetchEnv = new LinkedHashMap<>();
            fetchEnv.put("GIT_ASKPASS", askPass.toAbsolutePath().toString());
            fetchEnv.put("GIT_TERMINAL_PROMPT", "0");
            // Token stays in the fetch child environment only — never inside the askpass script.
            fetchEnv.put(
                GitProcess.ASKPASS_TOKEN_ENV,
                request.readToken() == null ? "" : request.readToken());

            String refspec = "refs/heads/" + request.targetBranch()
                + ":refs/remotes/origin/" + request.targetBranch();
            try {
                git.run(
                    repoDir,
                    List.of(
                        "-C", repoDir.toString(),
                        "fetch", "--no-tags", "--depth=50",
                        "origin", refspec),
                    fetchEnv);
            } finally {
                deleteQuietly(askPass);
                askPass = null;
            }

            String remoteSha = git.run(
                    repoDir,
                    List.of(
                        "-C", repoDir.toString(),
                        "rev-parse",
                        "refs/remotes/origin/" + request.targetBranch()))
                .strip();
            if (!remoteSha.equalsIgnoreCase(request.baseSha())) {
                throw new BaseMovedException(
                    "BASE_MOVED: target=" + remoteSha + " expected=" + request.baseSha());
            }

            git.run(
                repoDir,
                List.of("-C", repoDir.toString(), "checkout", "--detach", request.baseSha()));

            patchFile = Files.createTempFile(repoDir, "change-", ".patch");
            Files.write(patchFile, patch.patchBytes());
            String absolutePatch = patchFile.toAbsolutePath().normalize().toString();

            try {
                git.run(
                    repoDir,
                    List.of(
                        "-C", repoDir.toString(),
                        "apply", "--check", "--whitespace=error-all",
                        absolutePatch));
                git.run(
                    repoDir,
                    List.of(
                        "-C", repoDir.toString(),
                        "apply", "--index", "--whitespace=error-all",
                        absolutePatch));
            } catch (GitProcessException ex) {
                // Path traversal / unsafe paths often fail apply; classify escape if present in text.
                String msg = ex.getMessage() == null ? "" : ex.getMessage();
                if (msg.contains("..") || msg.contains("beyond a symbolic link")) {
                    throw new PatchPolicyException("PATH_ESCAPE: patch rejected by git apply", ex);
                }
                throw ex;
            }

            git.run(
                repoDir,
                List.of("-C", repoDir.toString(), "diff", "--cached", "--check"));

            String nameStatus = git.run(
                repoDir,
                List.of("-C", repoDir.toString(), "diff", "--cached", "--name-status", "-z"));
            List<String> recomputed = PatchPolicy.parseNameStatusNul(nameStatus);
            try {
                List<String> allowed = policy.validateChangedFiles(
                    recomputed,
                    request.forbiddenPaths(),
                    request.maxChangedFiles());
                return new Publication(request.baseSha(), allowed, repoDir);
            } catch (PatchPolicyException ex) {
                throw ex;
            }
        } catch (BaseMovedException | PatchPolicyException | GitProcessException ex) {
            deleteTreeQuietly(repoDir);
            throw ex;
        } catch (IOException ex) {
            deleteTreeQuietly(repoDir);
            throw new GitProcessException("GIT_APPLY_FAILED: " + ex.getMessage(), ex);
        } finally {
            deleteQuietly(askPass);
            deleteQuietly(patchFile);
        }
    }

    private Path createUniqueWorkDir() {
        try {
            if (Files.exists(workRoot) && Files.isSymbolicLink(workRoot)) {
                throw new GitProcessException("GIT_WORK_ROOT_SYMLINK: " + workRoot);
            }
            Files.createDirectories(workRoot);
            if (Files.isSymbolicLink(workRoot)) {
                throw new GitProcessException("GIT_WORK_ROOT_SYMLINK: " + workRoot);
            }
            Path dir = workRoot.resolve("pub-" + UUID.randomUUID());
            Files.createDirectories(dir);
            if (Files.isSymbolicLink(dir)) {
                throw new GitProcessException("GIT_WORK_DIR_SYMLINK: " + dir);
            }
            return dir.toAbsolutePath().normalize();
        } catch (IOException ex) {
            throw new GitProcessException("GIT_WORK_DIR_FAILED: " + ex.getMessage(), ex);
        }
    }

    private static Path resolveWorkRoot() {
        String env = System.getenv("LOOP_PUBLISH_WORK_ROOT");
        if (env == null || env.isBlank()) {
            throw new IllegalStateException("LOOP_PUBLISH_WORK_ROOT is not set");
        }
        return Path.of(env).toAbsolutePath().normalize();
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    private static void deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    deleteQuietly(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    deleteQuietly(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}

record PublicationRequest(
        String targetUrl,
        String targetBranch,
        String baseSha,
        List<String> forbiddenPaths,
        int maxChangedFiles,
        long maxPatchBytes,
        String readToken) {}

record VerifiedPatchInput(byte[] patchBytes, List<String> claimedPaths, String patchSha256) {
    VerifiedPatchInput {
        patchBytes = patchBytes == null ? new byte[0] : patchBytes.clone();
        claimedPaths = claimedPaths == null ? List.of() : List.copyOf(claimedPaths);
    }

    @Override
    public byte[] patchBytes() {
        return patchBytes.clone();
    }
}

record Publication(String parentSha, List<String> changedFiles, Path workTree) {
    Publication {
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
    }
}

final class BaseMovedException extends RuntimeException {
    BaseMovedException(String message) {
        super(message);
    }
}

final class PatchPolicyException extends RuntimeException {
    PatchPolicyException(String message) {
        super(message);
    }

    PatchPolicyException(String message, Throwable cause) {
        super(message, cause);
    }
}

final class GitProcessException extends RuntimeException {
    GitProcessException(String message) {
        super(message);
    }

    GitProcessException(String message, Throwable cause) {
        super(message, cause);
    }
}
