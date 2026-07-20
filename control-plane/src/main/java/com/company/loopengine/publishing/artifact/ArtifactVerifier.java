package com.company.loopengine.publishing.artifact;

import com.company.loopengine.publishing.artifact.VerifiedArtifact.ChangedFile;
import com.company.loopengine.publishing.artifact.VerifiedArtifact.EventLog;
import com.company.loopengine.publishing.artifact.VerifiedArtifact.ResultManifest;
import com.company.loopengine.publishing.artifact.VerifiedArtifact.ValidationResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Extracts and verifies Job Artifacts as hostile input before any trusted Git operation.
 */
public final class ArtifactVerifier {
    public static final int MAX_ENTRIES = 10_000;
    public static final long MAX_EXTRACTED_BYTES = 500L * 1024 * 1024;
    public static final long MAX_ENTRY_BYTES = 100L * 1024 * 1024;

    private static final Set<String> REQUIRED_FILES = Set.of(
        "out/change.patch",
        "out/result-manifest.json",
        "out/events.jsonl");
    private static final Pattern SHA1 = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        Pattern.CASE_INSENSITIVE);

    private final Path schemaPath;
    private final JsonMapper jsonMapper;

    public ArtifactVerifier(Path schemaPath) {
        this(schemaPath, JsonMapper.builder().build());
    }

    ArtifactVerifier(Path schemaPath, JsonMapper jsonMapper) {
        this.schemaPath = Objects.requireNonNull(schemaPath, "schemaPath").toAbsolutePath().normalize();
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        if (!Files.isRegularFile(this.schemaPath)) {
            throw new IllegalArgumentException("Missing schema: " + this.schemaPath);
        }
        try {
            JsonNode schema = this.jsonMapper.readTree(Files.readString(this.schemaPath, StandardCharsets.UTF_8));
            if (schema == null || !schema.path("$id").asString("").contains("result-manifest.schema.json")) {
                throw new IllegalArgumentException("Unexpected schema at " + this.schemaPath);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read schema: " + this.schemaPath, ex);
        }
    }

    public VerifiedArtifact verify(Path archive, ExpectedAttempt expected) {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(expected, "expected");
        Path root = createExtractionRoot();
        boolean ok = false;
        try {
            String archiveSha = sha256OfFile(archive);
            extractHostileArchive(archive, root);
            Path patchPath = root.resolve("out").resolve("change.patch");
            Path manifestPath = root.resolve("out").resolve("result-manifest.json");
            Path eventsPath = root.resolve("out").resolve("events.jsonl");
            byte[] patch = Files.readAllBytes(patchPath);
            byte[] events = Files.readAllBytes(eventsPath);
            String patchSha = sha256(patch);
            String eventsSha = sha256(events);
            ResultManifest manifest = parseAndValidateManifest(Files.readString(manifestPath, StandardCharsets.UTF_8));
            compareIdentity(manifest, expected);
            if (!patchSha.equalsIgnoreCase(manifest.patchSha256())) {
                throw new UnsafeArtifactException("DIGEST_MISMATCH");
            }
            if (patch.length == 0 || manifest.patchBytes() < 1 || manifest.patchBytes() != patch.length) {
                throw new UnsafeArtifactException("EMPTY_PATCH");
            }
            if (!eventsSha.equalsIgnoreCase(manifest.eventLog().sha256())) {
                throw new UnsafeArtifactException("DIGEST_MISMATCH");
            }
            if (manifest.eventLog().lastSeq() != expected.lastEventSeq()) {
                throw new UnsafeArtifactException("ATTEMPT_MISMATCH");
            }
            if (!"SUCCEEDED".equals(manifest.outcome())) {
                throw new UnsafeArtifactException("MANIFEST_OUTCOME");
            }
            for (ValidationResult validation : manifest.validationResults()) {
                if (validation.exitCode() != 0) {
                    throw new UnsafeArtifactException("VALIDATION_FAILED");
                }
            }
            ok = true;
            return new VerifiedArtifact(root, patch, patchSha, archiveSha, manifest, events);
        } catch (UnsafeArtifactException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new UnsafeArtifactException("ARCHIVE_IO_FAILURE", ex);
        } finally {
            if (!ok) {
                deleteRecursivelyQuietly(root);
            }
        }
    }

    private void extractHostileArchive(Path archive, Path root) throws IOException {
        Set<String> seen = new HashSet<>();
        long total = 0L;
        int entries = 0;
        boolean anyEntry = false;
        try (InputStream raw = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                anyEntry = true;
                entries++;
                if (entries > MAX_ENTRIES) {
                    throw new UnsafeArtifactException("ARCHIVE_TOO_MANY_ENTRIES");
                }
                String name = normalizeEntryName(entry.getName());
                rejectUnsafePath(name);
                if (entry.isDirectory()) {
                    if (name.equals("out") || name.equals("out/")) {
                        Files.createDirectories(root.resolve("out"));
                    }
                    continue;
                }
                if (name.indexOf('\0') >= 0) {
                    throw new UnsafeArtifactException("ARCHIVE_SYMLINK_FORBIDDEN");
                }
                if (!seen.add(name)) {
                    throw new UnsafeArtifactException("ARCHIVE_DUPLICATE_ENTRY");
                }
                if (!REQUIRED_FILES.contains(name)) {
                    throw new UnsafeArtifactException("ARCHIVE_UNEXPECTED_ENTRY");
                }
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root)) {
                    throw new UnsafeArtifactException("ARCHIVE_PATH_ESCAPE");
                }
                Files.createDirectories(target.getParent());
                long written = 0L;
                try (OutputStream out = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        written += read;
                        total += read;
                        if (written > MAX_ENTRY_BYTES) {
                            throw new UnsafeArtifactException("ARCHIVE_ENTRY_TOO_LARGE");
                        }
                        if (total > MAX_EXTRACTED_BYTES) {
                            throw new UnsafeArtifactException("ARCHIVE_TOO_LARGE");
                        }
                        out.write(buffer, 0, read);
                    }
                }
                if (Files.isSymbolicLink(target)) {
                    throw new UnsafeArtifactException("ARCHIVE_SYMLINK_FORBIDDEN");
                }
                zip.closeEntry();
            }
        } catch (UnsafeArtifactException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new UnsafeArtifactException("ARCHIVE_CORRUPT", ex);
        }
        if (!anyEntry) {
            throw new UnsafeArtifactException("ARCHIVE_CORRUPT");
        }
        if (!seen.containsAll(REQUIRED_FILES) || seen.size() != REQUIRED_FILES.size()) {
            throw new UnsafeArtifactException("ARCHIVE_REQUIRED_FILES");
        }
    }

    private ResultManifest parseAndValidateManifest(String json) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(json);
        } catch (RuntimeException ex) {
            throw new UnsafeArtifactException("MANIFEST_INVALID", ex);
        }
        if (root == null || !root.isObject()) {
            throw new UnsafeArtifactException("MANIFEST_INVALID");
        }
        // Enforce contracts/v1/result-manifest.schema.json without adding main-scope deps.
        requireNoUnknown(root, Set.of(
            "protocol", "taskId", "attemptId", "nodeId", "baseSha", "patchSha256", "patchBytes",
            "changedFiles", "validationResults", "eventLog", "startedAt", "finishedAt", "outcome"));
        String protocol = requireText(root, "protocol");
        if (!"v1".equals(protocol)) {
            throw new UnsafeArtifactException("MANIFEST_INVALID");
        }
        String taskId = requireUuid(root, "taskId");
        String attemptId = requireUuid(root, "attemptId");
        String nodeId = requireUuid(root, "nodeId");
        String baseSha = requireText(root, "baseSha").toLowerCase(Locale.ROOT);
        if (!SHA1.matcher(baseSha).matches()) {
            throw new UnsafeArtifactException("MANIFEST_INVALID");
        }
        String patchSha256 = requireText(root, "patchSha256").toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(patchSha256).matches()) {
            throw new UnsafeArtifactException("MANIFEST_INVALID");
        }
        if (!root.path("patchBytes").isIntegralNumber() || root.path("patchBytes").asLong() < 1) {
            throw new UnsafeArtifactException("MANIFEST_INVALID");
        }
        long patchBytes = root.path("patchBytes").asLong();
        List<ChangedFile> changedFiles = parseChangedFiles(root.path("changedFiles"));
        List<ValidationResult> validations = parseValidations(root.path("validationResults"));
        EventLog eventLog = parseEventLog(root.path("eventLog"));
        String startedAt = requireDateTime(root, "startedAt");
        String finishedAt = requireDateTime(root, "finishedAt");
        String outcome = requireText(root, "outcome");
        if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(outcome)) {
            throw new UnsafeArtifactException("MANIFEST_INVALID");
        }
        return new ResultManifest(
            protocol,
            taskId,
            attemptId,
            nodeId,
            baseSha,
            patchSha256,
            patchBytes,
            List.copyOf(changedFiles),
            List.copyOf(validations),
            eventLog,
            startedAt,
            finishedAt,
            outcome);
    }

    private static void compareIdentity(ResultManifest manifest, ExpectedAttempt expected) {
        if (!expected.taskId().toString().equalsIgnoreCase(manifest.taskId())
            || !expected.attemptId().toString().equalsIgnoreCase(manifest.attemptId())
            || !expected.nodeId().toString().equalsIgnoreCase(manifest.nodeId())
            || !expected.baseSha().equalsIgnoreCase(manifest.baseSha())) {
            throw new UnsafeArtifactException("ATTEMPT_MISMATCH");
        }
    }

    private static List<ChangedFile> parseChangedFiles(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw new UnsafeArtifactException("MANIFEST_INVALID");
        }
        List<ChangedFile> files = new ArrayList<>();
        for (JsonNode item : node) {
            requireNoUnknown(item, Set.of("path", "status"));
            String path = requireText(item, "path");
            if (path.isBlank()) {
                throw new UnsafeArtifactException("MANIFEST_INVALID");
            }
            String status = requireText(item, "status");
            if (!Set.of("added", "modified", "deleted", "renamed").contains(status)) {
                throw new UnsafeArtifactException("MANIFEST_INVALID");
            }
            files.add(new ChangedFile(path, status));
        }
        return files;
    }

    private static List<ValidationResult> parseValidations(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw new UnsafeArtifactException("MANIFEST_INVALID");
        }
        List<ValidationResult> results = new ArrayList<>();
        for (JsonNode item : node) {
            requireNoUnknown(item, Set.of("program", "args", "exitCode", "durationMs"));
            String program = requireText(item, "program");
            if (program.isBlank()) {
                throw new UnsafeArtifactException("MANIFEST_INVALID");
            }
            JsonNode argsNode = item.path("args");
            if (!argsNode.isArray()) {
                throw new UnsafeArtifactException("MANIFEST_INVALID");
            }
            List<String> args = new ArrayList<>();
            for (JsonNode arg : argsNode) {
                if (!arg.isString()) {
                    throw new UnsafeArtifactException("MANIFEST_INVALID");
                }
                args.add(arg.asString());
            }
            if (!item.path("exitCode").isIntegralNumber()) {
                throw new UnsafeArtifactException("MANIFEST_INVALID");
            }
            if (!item.path("durationMs").isIntegralNumber() || item.path("durationMs").asLong() < 0) {
                throw new UnsafeArtifactException("MANIFEST_INVALID");
            }
            results.add(new ValidationResult(
                program,
                List.copyOf(args),
                item.path("exitCode").asInt(),
                item.path("durationMs").asLong()));
        }
        return results;
    }

    private static EventLog parseEventLog(JsonNode node) {
        if (!node.isObject()) {
            throw new UnsafeArtifactException("MANIFEST_INVALID");
        }
        requireNoUnknown(node, Set.of("path", "sha256", "lastSeq"));
        String path = requireText(node, "path");
        if (!"events.jsonl".equals(path)) {
            throw new UnsafeArtifactException("MANIFEST_INVALID");
        }
        String sha = requireText(node, "sha256").toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(sha).matches()) {
            throw new UnsafeArtifactException("MANIFEST_INVALID");
        }
        if (!node.path("lastSeq").isIntegralNumber() || node.path("lastSeq").asLong() < 1) {
            throw new UnsafeArtifactException("MANIFEST_INVALID");
        }
        return new EventLog(path, sha, node.path("lastSeq").asLong());
    }

    private static void requireNoUnknown(JsonNode node, Set<String> allowed) {
        Iterator<String> names = node.propertyNames().iterator();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new UnsafeArtifactException("MANIFEST_INVALID");
            }
        }
        for (String required : allowed) {
            if (!node.has(required) || node.path(required).isMissingNode() || node.path(required).isNull()) {
                // eventLog/nested objects checked by callers; top-level required handled here
                if (!node.has(required)) {
                    throw new UnsafeArtifactException("MANIFEST_INVALID");
                }
            }
        }
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) {
            throw new UnsafeArtifactException("MANIFEST_INVALID");
        }
        return value.asString();
    }

    private static String requireUuid(JsonNode node, String field) {
        String value = requireText(node, field);
        if (!UUID_PATTERN.matcher(value).matches()) {
            throw new UnsafeArtifactException("MANIFEST_INVALID");
        }
        return value;
    }

    private static String requireDateTime(JsonNode node, String field) {
        String value = requireText(node, field);
        try {
            Instant.parse(value);
            return value;
        } catch (RuntimeException ex) {
            throw new UnsafeArtifactException("MANIFEST_INVALID", ex);
        }
    }

    private static String normalizeEntryName(String name) {
        return name.replace('\\', '/');
    }

    private static void rejectUnsafePath(String name) {
        if (name == null || name.isBlank()) {
            throw new UnsafeArtifactException("ARCHIVE_PATH_ESCAPE");
        }
        String normalized = name;
        if (normalized.startsWith("/") || normalized.startsWith("\\")) {
            throw new UnsafeArtifactException("ARCHIVE_PATH_ESCAPE");
        }
        if (normalized.length() >= 2 && Character.isLetter(normalized.charAt(0)) && normalized.charAt(1) == ':') {
            throw new UnsafeArtifactException("ARCHIVE_PATH_ESCAPE");
        }
        for (String part : normalized.split("/")) {
            if ("..".equals(part)) {
                throw new UnsafeArtifactException("ARCHIVE_PATH_ESCAPE");
            }
        }
    }

    private Path createExtractionRoot() {
        try {
            return Files.createTempDirectory("loop-artifact-verify-").toAbsolutePath().normalize();
        } catch (IOException ex) {
            throw new UnsafeArtifactException("ARCHIVE_IO_FAILURE", ex);
        }
    }

    private static String sha256OfFile(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * Attempt identity loaded from PostgreSQL for cross-check.
     */
    public record ExpectedAttempt(
            UUID taskId,
            UUID attemptId,
            UUID nodeId,
            String baseSha,
            long lastEventSeq) {
        public ExpectedAttempt {
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(baseSha, "baseSha");
        }
    }
}

/**
 * Stable failure codes for hostile Job Artifacts. Message is the code string.
 */
final class UnsafeArtifactException extends RuntimeException {
    UnsafeArtifactException(String code) {
        super(Objects.requireNonNull(code, "code"));
    }

    UnsafeArtifactException(String code, Throwable cause) {
        super(Objects.requireNonNull(code, "code"), cause);
    }

    String code() {
        return getMessage();
    }
}
