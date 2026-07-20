package com.company.loopengine.publishing.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactVerifierTest {
    private static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ATTEMPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID NODE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String BASE_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String PATCH = "diff --git a/src/Main.java b/src/Main.java\n+fixed\n";

    @TempDir
    Path temp;

    private ArtifactVerifier verifier;
    private Path schemaPath;

    @BeforeEach
    void setUp() {
        String basedir = System.getProperty("basedir");
        if (basedir == null || basedir.isBlank()) {
            basedir = System.getProperty("user.dir");
        }
        schemaPath = Path.of(basedir).resolve("../contracts/v1/result-manifest.schema.json").normalize();
        if (!Files.isRegularFile(schemaPath)) {
            schemaPath = Path.of("contracts/v1/result-manifest.schema.json").toAbsolutePath().normalize();
        }
        verifier = new ArtifactVerifier(schemaPath);
    }

    @Test
    void verifiesManifestAndEveryReferencedDigest() throws Exception {
        Path zip = fixture("valid-artifact.zip");
        VerifiedArtifact result = verifier.verify(zip, expectedAttempt());
        assertThat(result.patchSha256()).isEqualTo(sha256(result.patch()));
        assertThat(result.manifest().outcome()).isEqualTo("SUCCEEDED");
    }

    @Test
    void rejectsArchiveEntryOutsideExtractionRoot() throws Exception {
        assertThatThrownBy(() -> verifier.verify(fixture("zip-slip.zip"), expectedAttempt()))
            .isInstanceOf(UnsafeArtifactException.class)
            .hasMessageContaining("ARCHIVE_PATH_ESCAPE");
    }

    @Test
    void rejectsCorruptZipWithStableCode() throws Exception {
        Path corrupt = temp.resolve("corrupt.zip");
        Files.writeString(corrupt, "not-a-zip");
        assertThatThrownBy(() -> verifier.verify(corrupt, expectedAttempt()))
            .isInstanceOf(UnsafeArtifactException.class)
            .hasMessageContaining("ARCHIVE_CORRUPT");
    }

    @Test
    void rejectsOversizedExtractedFile() throws Exception {
        assertThatThrownBy(() -> verifier.verify(fixture("oversized.zip"), expectedAttempt()))
            .isInstanceOf(UnsafeArtifactException.class)
            .hasMessageContaining("ARCHIVE_ENTRY_TOO_LARGE");
    }

    @Test
    void rejectsWrongPatchDigest() throws Exception {
        assertThatThrownBy(() -> verifier.verify(fixture("wrong-digest.zip"), expectedAttempt()))
            .isInstanceOf(UnsafeArtifactException.class)
            .hasMessageContaining("DIGEST_MISMATCH");
    }

    @Test
    void rejectsWrongAttemptIdentity() throws Exception {
        assertThatThrownBy(() -> verifier.verify(fixture("valid-artifact.zip"), wrongAttempt()))
            .isInstanceOf(UnsafeArtifactException.class)
            .hasMessageContaining("ATTEMPT_MISMATCH");
    }

    private ArtifactVerifier.ExpectedAttempt expectedAttempt() {
        return new ArtifactVerifier.ExpectedAttempt(TASK_ID, ATTEMPT_ID, NODE_ID, BASE_SHA, 3L);
    }

    private ArtifactVerifier.ExpectedAttempt wrongAttempt() {
        return new ArtifactVerifier.ExpectedAttempt(
            TASK_ID,
            UUID.fromString("99999999-9999-9999-9999-999999999999"),
            NODE_ID,
            BASE_SHA,
            3L);
    }

    private Path fixture(String name) throws Exception {
        Path zip = temp.resolve(name);
        return switch (name) {
            case "valid-artifact.zip" -> writeValidArtifact(zip, PATCH, sha256(PATCH), ATTEMPT_ID);
            case "zip-slip.zip" -> writeZipSlip(zip);
            case "oversized.zip" -> writeOversized(zip);
            case "wrong-digest.zip" -> writeValidArtifact(
                zip,
                PATCH,
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                ATTEMPT_ID);
            default -> throw new IllegalArgumentException(name);
        };
    }

    private Path writeValidArtifact(Path zip, String patch, String declaredPatchSha, UUID attemptId)
            throws Exception {
        byte[] patchBytes = patch.getBytes(StandardCharsets.UTF_8);
        byte[] events = """
            {"seq":1}
            {"seq":2}
            {"seq":3}
            """.getBytes(StandardCharsets.UTF_8);
        String eventSha = sha256(events);
        String manifest = """
            {
              "protocol":"v1",
              "taskId":"%s",
              "attemptId":"%s",
              "nodeId":"%s",
              "baseSha":"%s",
              "patchSha256":"%s",
              "patchBytes":%d,
              "changedFiles":[{"path":"src/Main.java","status":"modified"}],
              "validationResults":[
                {"program":"mvn","args":["-q","test"],"exitCode":0,"durationMs":1200}
              ],
              "eventLog":{
                "path":"events.jsonl",
                "sha256":"%s",
                "lastSeq":3
              },
              "startedAt":"2026-07-18T08:00:00Z",
              "finishedAt":"2026-07-18T08:05:00Z",
              "outcome":"SUCCEEDED"
            }
            """.formatted(
            TASK_ID,
            attemptId,
            NODE_ID,
            BASE_SHA,
            declaredPatchSha,
            patchBytes.length,
            eventSha);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "out/change.patch", patchBytes);
            put(out, "out/result-manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
            put(out, "out/events.jsonl", events);
        }
        return zip;
    }

    private Path writeZipSlip(Path zip) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "../escape.txt", "pwned".getBytes(StandardCharsets.UTF_8));
        }
        return zip;
    }

    private Path writeOversized(Path zip) throws Exception {
        long size = 100L * 1024 * 1024 + 1;
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            ZipEntry entry = new ZipEntry("out/change.patch");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(size);
            entry.setCompressedSize(size);
            CRC32 crc = new CRC32();
            byte[] chunk = new byte[8192];
            long remaining = size;
            while (remaining > 0) {
                int n = (int) Math.min(chunk.length, remaining);
                crc.update(chunk, 0, n);
                remaining -= n;
            }
            entry.setCrc(crc.getValue());
            out.putNextEntry(entry);
            remaining = size;
            while (remaining > 0) {
                int n = (int) Math.min(chunk.length, remaining);
                out.write(chunk, 0, n);
                remaining -= n;
            }
            out.closeEntry();
            put(out, "out/result-manifest.json", "{}".getBytes(StandardCharsets.UTF_8));
            put(out, "out/events.jsonl", "\n".getBytes(StandardCharsets.UTF_8));
        }
        return zip;
    }

    private static void put(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
