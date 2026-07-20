package com.company.loopengine.gitlab.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitLab Merge Request API used by Publisher to create or recover MRs idempotently.
 */
public interface GitLabMergeRequestClient {
    List<RemoteMergeRequest> searchBySourceBranch(long projectId, String sourceBranch);

    RemoteMergeRequest create(long projectId, CreateMergeRequestRequest request);

    List<Long> resolveReviewerGroupMembers(String groupPath);

    record RemoteMergeRequest(
            long iid,
            String webUrl,
            String sourceBranch,
            String targetBranch,
            String description
    ) {
        public RemoteMergeRequest {
            Objects.requireNonNull(webUrl, "webUrl");
            Objects.requireNonNull(sourceBranch, "sourceBranch");
            Objects.requireNonNull(targetBranch, "targetBranch");
            Objects.requireNonNull(description, "description");
        }
    }

    record CreateMergeRequestRequest(
            String sourceBranch,
            String targetBranch,
            String title,
            String description,
            boolean removeSourceBranch,
            boolean squash,
            List<Long> reviewerIds
    ) {
        public CreateMergeRequestRequest {
            Objects.requireNonNull(sourceBranch, "sourceBranch");
            Objects.requireNonNull(targetBranch, "targetBranch");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(description, "description");
            reviewerIds = List.copyOf(Objects.requireNonNull(reviewerIds, "reviewerIds"));
        }
    }

    final class AmbiguousMergeRequestException extends RuntimeException {
        public AmbiguousMergeRequestException(String message) {
            super(message);
        }

        public AmbiguousMergeRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    final class TerminalMergeRequestException extends RuntimeException {
        public TerminalMergeRequestException(String message) {
            super(message);
        }
    }

    /**
     * HTTP implementation of the Merge Request client.
     */
    final class HttpGitLabMergeRequestClient implements GitLabMergeRequestClient {
        private static final Pattern OBJECT_BLOCK = Pattern.compile("\\{[^{}]*\\}");
        private static final Pattern IID_FIELD = Pattern.compile("\"iid\"\\s*:\\s*(\\d+)");
        private static final Pattern WEB_URL_FIELD =
            Pattern.compile("\"web_url\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"])*)\"");
        private static final Pattern SOURCE_BRANCH_FIELD =
            Pattern.compile("\"source_branch\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"])*)\"");
        private static final Pattern TARGET_BRANCH_FIELD =
            Pattern.compile("\"target_branch\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"])*)\"");
        private static final Pattern DESCRIPTION_FIELD =
            Pattern.compile("\"description\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"])*)\"");
        private static final Pattern ID_FIELD = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

        private final String baseUrl;
        private final String token;
        private final HttpClient http;

        public HttpGitLabMergeRequestClient(String baseUrl, String token) {
            this(baseUrl, token, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build());
        }

        HttpGitLabMergeRequestClient(String baseUrl, String token, HttpClient http) {
            this.baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
            this.token = Objects.requireNonNull(token, "token");
            this.http = Objects.requireNonNull(http, "http");
        }

        @Override
        public List<RemoteMergeRequest> searchBySourceBranch(long projectId, String sourceBranch) {
            Objects.requireNonNull(sourceBranch, "sourceBranch");
            String encoded = URLEncoder.encode(sourceBranch, StandardCharsets.UTF_8);
            // state=all covers open and closed MRs for idempotent recovery
            String path = "/api/v4/projects/" + projectId
                + "/merge_requests?source_branch=" + encoded
                + "&state=all&per_page=100";
            String body = send("GET", path, null);
            return parseMergeRequests(body);
        }

        @Override
        public RemoteMergeRequest create(long projectId, CreateMergeRequestRequest request) {
            Objects.requireNonNull(request, "request");
            StringBuilder json = new StringBuilder();
            json.append('{');
            json.append("\"source_branch\":\"").append(escapeJson(request.sourceBranch())).append('"');
            json.append(",\"target_branch\":\"").append(escapeJson(request.targetBranch())).append('"');
            json.append(",\"title\":\"").append(escapeJson(request.title())).append('"');
            json.append(",\"description\":\"").append(escapeJson(request.description())).append('"');
            json.append(",\"remove_source_branch\":").append(request.removeSourceBranch());
            json.append(",\"squash\":").append(request.squash());
            json.append(",\"reviewer_ids\":[");
            for (int i = 0; i < request.reviewerIds().size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(request.reviewerIds().get(i));
            }
            json.append("]}");
            String body = send("POST", "/api/v4/projects/" + projectId + "/merge_requests", json.toString());
            List<RemoteMergeRequest> parsed = parseMergeRequests("[" + body + "]");
            if (parsed.isEmpty()) {
                // single object response
                RemoteMergeRequest single = parseSingleMergeRequest(body);
                if (single == null) {
                    throw new TerminalMergeRequestException("GitLab create MR response missing iid");
                }
                return single;
            }
            return parsed.getFirst();
        }

        @Override
        public List<Long> resolveReviewerGroupMembers(String groupPath) {
            Objects.requireNonNull(groupPath, "groupPath");
            String encoded = URLEncoder.encode(groupPath, StandardCharsets.UTF_8)
                .replace("+", "%20");
            String body = send("GET", "/api/v4/groups/" + encoded + "/members?per_page=100", null);
            Set<Long> ids = new LinkedHashSet<>();
            Matcher matcher = ID_FIELD.matcher(body);
            while (matcher.find()) {
                ids.add(Long.parseLong(matcher.group(1)));
            }
            return List.copyOf(ids);
        }

        private List<RemoteMergeRequest> parseMergeRequests(String json) {
            List<RemoteMergeRequest> results = new ArrayList<>();
            Matcher objects = OBJECT_BLOCK.matcher(json);
            while (objects.find()) {
                RemoteMergeRequest mr = parseSingleMergeRequest(objects.group());
                if (mr != null) {
                    results.add(mr);
                }
            }
            return List.copyOf(results);
        }

        private static RemoteMergeRequest parseSingleMergeRequest(String block) {
            Matcher iid = IID_FIELD.matcher(block);
            Matcher webUrl = WEB_URL_FIELD.matcher(block);
            Matcher source = SOURCE_BRANCH_FIELD.matcher(block);
            Matcher target = TARGET_BRANCH_FIELD.matcher(block);
            if (!iid.find() || !webUrl.find() || !source.find() || !target.find()) {
                return null;
            }
            String description = "";
            Matcher desc = DESCRIPTION_FIELD.matcher(block);
            if (desc.find()) {
                description = unescapeJson(desc.group(1));
            }
            return new RemoteMergeRequest(
                Long.parseLong(iid.group(1)),
                unescapeJson(webUrl.group(1)),
                unescapeJson(source.group(1)),
                unescapeJson(target.group(1)),
                description);
        }

        private String send(String method, String path, String jsonBody) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("PRIVATE-TOKEN", token)
                    .header("Accept", "application/json");
                if (jsonBody == null) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
                }
                HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 401 || status == 403 || status == 404) {
                    throw new TerminalMergeRequestException("GitLab HTTP " + status);
                }
                if (status == 429 || status >= 500) {
                    throw new AmbiguousMergeRequestException("GitLab HTTP " + status);
                }
                if (status < 200 || status >= 300) {
                    throw new TerminalMergeRequestException("GitLab HTTP " + status);
                }
                return response.body() == null ? "" : response.body();
            } catch (AmbiguousMergeRequestException | TerminalMergeRequestException ex) {
                throw ex;
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new AmbiguousMergeRequestException(
                    "GitLab transport failure: " + ex.getMessage(), ex);
            }
        }

        private static String escapeJson(String value) {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        }

        private static String unescapeJson(String value) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\\' && i + 1 < value.length()) {
                    char next = value.charAt(++i);
                    out.append(switch (next) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case '"' -> '"';
                        case '\\' -> '\\';
                        default -> next;
                    });
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        private static String trimTrailingSlash(String url) {
            if (url.endsWith("/")) {
                return url.substring(0, url.length() - 1);
            }
            return url;
        }
    }
}
