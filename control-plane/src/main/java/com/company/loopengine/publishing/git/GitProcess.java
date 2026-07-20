package com.company.loopengine.publishing.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Runs Git via argument vectors only — never through a shell.
 */
public final class GitProcess {
    /**
     * Process-local env var holding the robot read token for the ephemeral ASKPASS helper.
     * The token is never written into the ASKPASS script body.
     */
    public static final String ASKPASS_TOKEN_ENV = "LOOP_ENGINE_GIT_ASKPASS_TOKEN";

    private static final Logger LOG = Logger.getLogger(GitProcess.class.getName());
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    /**
     * Fixed POSIX askpass program. Prints the token from {@link #ASKPASS_TOKEN_ENV} only.
     * Compatible with Linux/macOS {@code execve} shebang and with Git for Windows when {@code sh}
     * can interpret the script; the script never embeds credential bytes.
     */
    private static final String ASKPASS_SCRIPT = """
        #!/bin/sh
        # Ephemeral GIT_ASKPASS for Loop Engine Publisher.
        # Credential bytes arrive only via LOOP_ENGINE_GIT_ASKPASS_TOKEN in the process
        # environment of the git fetch child — never via this file's contents.
        printf '%s\\n' "${LOOP_ENGINE_GIT_ASKPASS_TOKEN-}"
        """;

    private final String gitExecutable;
    private final Duration timeout;

    public GitProcess() {
        this("git", DEFAULT_TIMEOUT);
    }

    public GitProcess(String gitExecutable, Duration timeout) {
        this.gitExecutable = Objects.requireNonNull(gitExecutable, "gitExecutable");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    public String run(Path cwd, List<String> args) {
        return run(cwd, args, Map.of());
    }

    public String run(Path cwd, List<String> args, Map<String, String> extraEnv) {
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(extraEnv, "extraEnv");

        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(gitExecutable);
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.putAll(extraEnv);

        LOG.fine(() -> "git argv=" + redact(command) + " cwd=" + cwd);

        try {
            Process process = pb.start();
            byte[] raw = process.getInputStream().readAllBytes();
            String output = new String(raw, StandardCharsets.UTF_8);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GitProcessException("GIT_TIMEOUT: git exceeded " + timeout);
            }
            if (process.exitValue() != 0) {
                throw new GitProcessException(
                    "GIT_APPLY_FAILED: exit=" + process.exitValue() + " output=" + redactOutput(output));
            }
            return output;
        } catch (IOException ex) {
            throw new GitProcessException("GIT_APPLY_FAILED: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GitProcessException("GIT_TIMEOUT: interrupted", ex);
        }
    }

    /**
     * Creates an ephemeral POSIX {@code GIT_ASKPASS} helper under {@code workDir}.
     * Callers must pass the read token only through {@link #ASKPASS_TOKEN_ENV} on the fetch
     * process environment and delete this file immediately after fetch.
     */
    public Path writeAskPass(Path workDir) {
        Objects.requireNonNull(workDir, "workDir");
        try {
            Path script = Files.createTempFile(workDir, "askpass-", ".sh");
            Files.writeString(script, ASKPASS_SCRIPT, StandardCharsets.UTF_8);
            makeExecutable(script);
            return script;
        } catch (IOException ex) {
            throw new GitProcessException("GIT_ASKPASS_FAILED: " + ex.getMessage(), ex);
        }
    }

    static void makeExecutable(Path script) throws IOException {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(script, perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows / non-POSIX: fall back to File#setExecutable.
            if (!script.toFile().setExecutable(true, true)) {
                script.toFile().setExecutable(true);
            }
        }
    }

    static List<String> redact(List<String> command) {
        List<String> copy = new ArrayList<>(command.size());
        for (String part : command) {
            copy.add(redactTokenish(part));
        }
        return copy;
    }

    static String redactOutput(String output) {
        if (output == null) {
            return "";
        }
        return output.length() > 2_000 ? output.substring(0, 2_000) + "…" : output;
    }

    private static String redactTokenish(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("token") || lower.contains("password") || lower.contains("askpass")) {
            return "<redacted>";
        }
        return value;
    }
}
