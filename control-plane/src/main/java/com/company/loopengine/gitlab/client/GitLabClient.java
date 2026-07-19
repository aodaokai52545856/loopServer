package com.company.loopengine.gitlab.client;

public interface GitLabClient {
    void updateRepairLabel(long projectId, long issueIid, String targetLabel);

    void ensureNote(long projectId, long issueIid, String marker, String markdown);

    sealed class GitLabApiException extends RuntimeException {
        public GitLabApiException(String message) {
            super(message);
        }
    }

    final class GitLabTerminalException extends GitLabApiException {
        public GitLabTerminalException(String message) {
            super(message);
        }
    }

    final class GitLabRetryableException extends GitLabApiException {
        public GitLabRetryableException(String message) {
            super(message);
        }
    }
}
