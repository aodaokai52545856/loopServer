package com.company.loopengine.publishing.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Runs Git via argument vectors only — never through a shell.
 */
public final class GitProcess {
    private static final Logger LOG = Logger.getLogger(GitProcess.class.getName());
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

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

    public Path writeAskPass(Path workDir, String token) {
        try {
            Path script = Files.createTempFile(workDir, "askpass-", ".cmd");
            String body = token == null ? "" : token.replace("\r", "").replace("\n", "");
            // Minimal Windows askpass helper; deleted immediately after fetch.
            Files.writeString(
                script,
                "@echo off\r\necho " + body + "\r\n",
                StandardCharsets.UTF_8);
            try {
                script.toFile().setExecutable(true);
            } catch (SecurityException ignored) {
                // best-effort on platforms that ignore the bit
            }
            return script;
        } catch (IOException ex) {
            throw new GitProcessException("GIT_ASKPASS_FAILED: " + ex.getMessage(), ex);
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
