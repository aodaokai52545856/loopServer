package com.company.loopengine.publishing.artifact;

import java.nio.file.Path;
import java.util.List;

/**
 * Artifact contents that passed hostile-input verification and may proceed to trusted Git apply.
 */
public record VerifiedArtifact(
        Path extractionRoot,
        byte[] patch,
        String patchSha256,
        String archiveSha256,
        ResultManifest manifest,
        byte[] eventsJsonl) {

    public VerifiedArtifact {
        patch = patch == null ? new byte[0] : patch.clone();
        eventsJsonl = eventsJsonl == null ? new byte[0] : eventsJsonl.clone();
    }

    @Override
    public byte[] patch() {
        return patch.clone();
    }

    @Override
    public byte[] eventsJsonl() {
        return eventsJsonl.clone();
    }

    public record ResultManifest(
            String protocol,
            String taskId,
            String attemptId,
            String nodeId,
            String baseSha,
            String patchSha256,
            long patchBytes,
            List<ChangedFile> changedFiles,
            List<ValidationResult> validationResults,
            EventLog eventLog,
            String startedAt,
            String finishedAt,
            String outcome) {}

    public record ChangedFile(String path, String status) {}

    public record ValidationResult(String program, List<String> args, int exitCode, long durationMs) {}

    public record EventLog(String path, String sha256, long lastSeq) {}
}
