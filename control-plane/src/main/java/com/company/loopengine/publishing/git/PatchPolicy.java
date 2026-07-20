package com.company.loopengine.publishing.git;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recomputes and enforces profile patch policy against Git index paths — never trusts the manifest.
 */
public final class PatchPolicy {
    private static final Pattern ABSOLUTE_WINDOWS = Pattern.compile("^[A-Za-z]:[\\\\/]");

    public void validatePatchBytes(byte[] patch, long maxPatchBytes) {
        Objects.requireNonNull(patch, "patch");
        if (patch.length == 0) {
            throw new PatchPolicyException("EMPTY_PATCH: patch bytes are empty");
        }
        if (maxPatchBytes > 0 && patch.length > maxPatchBytes) {
            throw new PatchPolicyException(
                "PATCH_TOO_LARGE: " + patch.length + " exceeds maxPatchBytes " + maxPatchBytes);
        }
        String text = new String(patch, java.nio.charset.StandardCharsets.UTF_8);
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("binary files ") || lower.contains("git binary patch")) {
            throw new PatchPolicyException("BINARY_PATCH: binary patches are not allowed");
        }
    }

    /** Scans unified-diff path headers before git apply so traversal fails with a stable code. */
    public void rejectUnsafePatchPaths(byte[] patch) {
        Objects.requireNonNull(patch, "patch");
        String text = new String(patch, java.nio.charset.StandardCharsets.UTF_8);
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("diff --git ")) {
                for (String token : trimmed.substring("diff --git ".length()).split(" ")) {
                    String path = stripDiffPrefix(token);
                    if (!path.isEmpty()) {
                        rejectEscaping(normalize(path));
                    }
                }
            } else if (trimmed.startsWith("+++ ") || trimmed.startsWith("--- ")) {
                String path = stripDiffPrefix(trimmed.substring(4).trim());
                if (!path.isEmpty() && !"/dev/null".equals(path)) {
                    rejectEscaping(normalize(path));
                }
            } else if (trimmed.startsWith("rename from ")) {
                rejectEscaping(normalize(trimmed.substring("rename from ".length()).trim()));
            } else if (trimmed.startsWith("rename to ")) {
                rejectEscaping(normalize(trimmed.substring("rename to ".length()).trim()));
            } else if (trimmed.startsWith("copy from ")) {
                rejectEscaping(normalize(trimmed.substring("copy from ".length()).trim()));
            } else if (trimmed.startsWith("copy to ")) {
                rejectEscaping(normalize(trimmed.substring("copy to ".length()).trim()));
            }
        }
    }

    private static String stripDiffPrefix(String token) {
        if (token == null) {
            return "";
        }
        String value = token.trim();
        if (value.startsWith("a/") || value.startsWith("b/")) {
            value = value.substring(2);
        }
        return value;
    }

    public List<String> validateChangedFiles(
            List<String> changedFiles,
            List<String> forbiddenPaths,
            int maxChangedFiles) {
        Objects.requireNonNull(changedFiles, "changedFiles");
        Objects.requireNonNull(forbiddenPaths, "forbiddenPaths");

        Set<String> unique = new LinkedHashSet<>();
        for (String raw : changedFiles) {
            String path = normalize(raw);
            rejectEscaping(path);
            if (isForbidden(path, forbiddenPaths)) {
                throw new PatchPolicyException("FORBIDDEN_PATH: " + path);
            }
            unique.add(path);
        }
        if (unique.isEmpty()) {
            throw new PatchPolicyException("EMPTY_PATCH: no changed files after apply");
        }
        if (maxChangedFiles > 0 && unique.size() > maxChangedFiles) {
            throw new PatchPolicyException(
                "TOO_MANY_FILES: " + unique.size() + " exceeds maxChangedFiles " + maxChangedFiles);
        }
        return List.copyOf(unique);
    }

    static String normalize(String path) {
        if (path == null || path.isBlank()) {
            throw new PatchPolicyException("PATH_ESCAPE: blank path");
        }
        String cleaned = path.replace('\\', '/').trim();
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        return cleaned;
    }

    static void rejectEscaping(String path) {
        if (path.indexOf('\0') >= 0) {
            throw new PatchPolicyException("PATH_ESCAPE: NUL in path");
        }
        if (path.startsWith("/") || ABSOLUTE_WINDOWS.matcher(path).find() || path.startsWith("//")) {
            throw new PatchPolicyException("PATH_ESCAPE: absolute path " + path);
        }
        Path relative = Path.of(path);
        if (relative.isAbsolute()) {
            throw new PatchPolicyException("PATH_ESCAPE: absolute path " + path);
        }
        for (Path part : relative) {
            if ("..".equals(part.toString())) {
                throw new PatchPolicyException("PATH_ESCAPE: " + path);
            }
        }
    }

    static boolean isForbidden(String path, List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            String p = pattern.replace('\\', '/');
            if (matchForbidden(path, p)) {
                return true;
            }
        }
        return false;
    }

    static boolean matchForbidden(String path, String pattern) {
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        if (globMatches(pattern, path)) {
            return true;
        }
        int slash = path.lastIndexOf('/');
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        return globMatches(pattern, base);
    }

    private static boolean globMatches(String pattern, String value) {
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            return matcher.matches(Path.of(value));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Parses {@code git diff --cached --name-status -z} into path list (both sides of renames). */
    public static List<String> parseNameStatusNul(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        String[] parts = raw.split("\0", -1);
        List<String> paths = new ArrayList<>();
        int i = 0;
        while (i < parts.length) {
            String status = parts[i];
            if (status == null || status.isEmpty()) {
                i++;
                continue;
            }
            char code = status.charAt(0);
            if (code == 'R' || code == 'C') {
                if (i + 2 >= parts.length) {
                    break;
                }
                paths.add(parts[i + 1]);
                paths.add(parts[i + 2]);
                i += 3;
            } else {
                if (i + 1 >= parts.length) {
                    break;
                }
                paths.add(parts[i + 1]);
                i += 2;
            }
        }
        return paths;
    }
}
