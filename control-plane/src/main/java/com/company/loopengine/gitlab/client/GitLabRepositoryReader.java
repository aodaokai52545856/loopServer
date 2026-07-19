package com.company.loopengine.gitlab.client;

import java.io.InputStream;

/**
 * Server-side GitLab repository/attachment reader used while routing queued defects.
 */
public interface GitLabRepositoryReader {
    String resolveBranchHead(String repositoryPath, String branch);

    AttachmentStream openAttachment(String sourceUrl);

    record AttachmentStream(String contentType, long contentLength, InputStream body) implements AutoCloseable {
        @Override
        public void close() {
            try {
                body.close();
            } catch (Exception ignored) {
                // best-effort close after a single streaming pass
            }
        }
    }
}
