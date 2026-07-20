package com.company.loopengine.publishing.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Downloads GitLab Job Artifacts as bounded hostile input.
 */
public final class ArtifactDownloader {
    public static final long MAX_RESPONSE_BYTES = 200L * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    private final String baseUrl;
    private final String token;
    private final HttpClient http;

    public ArtifactDownloader(String baseUrl, String token) {
        this(baseUrl, token, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    }

    ArtifactDownloader(String baseUrl, String token, HttpClient http) {
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.token = Objects.requireNonNull(token, "token");
        this.http = Objects.requireNonNull(http, "http");
    }

    /**
     * Downloads {@code GET /projects/{centralProjectId}/jobs/{jobId}/artifacts} to a new temp file,
     * computing archive SHA-256 while streaming.
     */
    public DownloadedArtifact download(long centralProjectId, long jobId) {
        Path temp;
        try {
            temp = Files.createTempFile("loop-artifact-", ".zip");
        } catch (IOException ex) {
            throw new UnsafeArtifactException("ARCHIVE_IO_FAILURE", ex);
        }
        boolean success = false;
        try {
            URI uri = URI.create(baseUrl + "/api/v4/projects/" + centralProjectId + "/jobs/" + jobId + "/artifacts");
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(RESPONSE_TIMEOUT)
                .header("PRIVATE-TOKEN", token)
                .header("Accept", "application/octet-stream")
                .GET()
                .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new UnsafeArtifactException("ARCHIVE_DOWNLOAD_HTTP_" + status);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0L;
            try (InputStream body = new DigestInputStream(response.body(), digest);
                 OutputStream out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = body.read(buffer)) >= 0) {
                    size += read;
                    if (size > MAX_RESPONSE_BYTES) {
                        throw new UnsafeArtifactException("ARCHIVE_DOWNLOAD_TOO_LARGE");
                    }
                    out.write(buffer, 0, read);
                }
            }
            success = true;
            return new DownloadedArtifact(temp, HexFormat.of().formatHex(digest.digest()), size);
        } catch (UnsafeArtifactException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UnsafeArtifactException("ARCHIVE_DOWNLOAD_INTERRUPTED", ex);
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new UnsafeArtifactException("ARCHIVE_DOWNLOAD_FAILED", ex);
        } finally {
            if (!success) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public record DownloadedArtifact(Path archive, String archiveSha256, long sizeBytes) {}
}
