package com.company.loopengine.defect.attachment;

import com.company.loopengine.gitlab.client.GitLabRepositoryReader;
import com.company.loopengine.gitlab.client.GitLabRepositoryReader.AttachmentStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Streams GitLab attachments once to compute size and SHA-256 without retaining bytes.
 */
public final class AttachmentInspector {
    public static final long MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;
    public static final int MAX_ATTACHMENTS = 10;

    private static final Set<String> ALLOWED_TYPES = Set.of(
        "image/png",
        "image/jpeg",
        "image/gif",
        "image/webp",
        "text/plain",
        "application/pdf");

    public List<InspectedAttachment> inspect(List<String> sourceUrls, GitLabRepositoryReader gitlab) {
        if (sourceUrls.size() > MAX_ATTACHMENTS) {
            throw new AttachmentValidationException("ATTACHMENT_COUNT_EXCEEDED");
        }
        List<InspectedAttachment> inspected = new ArrayList<>();
        long total = 0L;
        for (String sourceUrl : sourceUrls) {
            try (AttachmentStream stream = gitlab.openAttachment(sourceUrl)) {
                String contentType = normalizeContentType(stream.contentType());
                if (!ALLOWED_TYPES.contains(contentType)) {
                    throw new AttachmentValidationException("ATTACHMENT_TYPE_NOT_ALLOWED");
                }
                if (stream.contentLength() > MAX_ATTACHMENT_BYTES) {
                    throw new AttachmentValidationException("ATTACHMENT_TOO_LARGE");
                }
                DigestResult digest = digestOnce(stream.body(), MAX_ATTACHMENT_BYTES);
                if (digest.sizeBytes() > MAX_ATTACHMENT_BYTES) {
                    throw new AttachmentValidationException("ATTACHMENT_TOO_LARGE");
                }
                total += digest.sizeBytes();
                if (total > MAX_TOTAL_BYTES) {
                    throw new AttachmentValidationException("ATTACHMENT_TOTAL_TOO_LARGE");
                }
                inspected.add(new InspectedAttachment(
                    sourceUrl, contentType, digest.sizeBytes(), digest.sha256()));
            }
        }
        return List.copyOf(inspected);
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        String trimmed = contentType.trim().toLowerCase(Locale.ROOT);
        int semi = trimmed.indexOf(';');
        return semi >= 0 ? trimmed.substring(0, semi).trim() : trimmed;
    }

    private static DigestResult digestOnce(InputStream body, long maxBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DigestInputStream in = new DigestInputStream(body, digest);
            byte[] buffer = new byte[8192];
            long size = 0L;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                size += read;
                if (size > maxBytes) {
                    return new DigestResult(size, "");
                }
            }
            return new DigestResult(size, HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException | IOException ex) {
            throw new IllegalStateException("Unable to hash attachment", ex);
        }
    }

    public record InspectedAttachment(
        String sourceUrl, String contentType, long sizeBytes, String sha256) {}

    private record DigestResult(long sizeBytes, String sha256) {}

    public static final class AttachmentValidationException extends RuntimeException {
        private final String reasonCode;

        public AttachmentValidationException(String reasonCode) {
            super(reasonCode);
            this.reasonCode = reasonCode;
        }

        public String reasonCode() {
            return reasonCode;
        }
    }
}
