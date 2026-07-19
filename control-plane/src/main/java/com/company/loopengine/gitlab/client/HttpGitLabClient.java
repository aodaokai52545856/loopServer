package com.company.loopengine.gitlab.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HttpGitLabClient implements GitLabClient {
    private static final Pattern STRING_ARRAY = Pattern.compile("\"((?:\\\\.|[^\\\\\"])*)\"");

    private final String baseUrl;
    private final String token;
    private final HttpClient http;

    public HttpGitLabClient(String baseUrl, String token) {
        this(baseUrl, token, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build());
    }

    HttpGitLabClient(String baseUrl, String token, HttpClient http) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.token = token;
        this.http = http;
    }

    @Override
    public void updateRepairLabel(long projectId, long issueIid, String targetLabel) {
        String issueJson = send("GET", issuePath(projectId, issueIid), null);
        List<String> labels = new ArrayList<>();
        for (String name : readStringArrayField(issueJson, "labels")) {
            if (!name.startsWith("repair::")) {
                labels.add(name);
            }
        }
        labels.add(targetLabel);
        String joined = String.join(",", labels.stream().map(HttpGitLabClient::escapeJson).toList());
        send("PUT", issuePath(projectId, issueIid), "{\"labels\":\"" + joined + "\"}");
    }

    @Override
    public void ensureNote(long projectId, long issueIid, String marker, String markdown) {
        String notesJson = send(
            "GET",
            issuePath(projectId, issueIid) + "/notes?per_page=100",
            null);
        for (String noteBody : readObjectStringFields(notesJson, "body")) {
            if (noteBody.contains(marker)) {
                return;
            }
        }
        send("POST", issuePath(projectId, issueIid) + "/notes",
            "{\"body\":\"" + escapeJson(markdown) + "\"}");
    }

    private String issuePath(long projectId, long issueIid) {
        return "/api/v4/projects/" + projectId + "/issues/" + issueIid;
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
                throw new GitLabTerminalException("GitLab HTTP " + status);
            }
            if (status == 429 || status >= 500) {
                throw new GitLabRetryableException("GitLab HTTP " + status);
            }
            if (status < 200 || status >= 300) {
                throw new GitLabTerminalException("GitLab HTTP " + status);
            }
            return response.body() == null ? "" : response.body();
        } catch (GitLabApiException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new GitLabRetryableException("GitLab transport failure: " + ex.getMessage());
        }
    }

    public static List<String> readStringArrayField(String json, String field) {
        int fieldIdx = json.indexOf('"' + field + '"');
        if (fieldIdx < 0) {
            return List.of();
        }
        int arrayStart = json.indexOf('[', fieldIdx);
        int arrayEnd = json.indexOf(']', arrayStart);
        if (arrayStart < 0 || arrayEnd < 0) {
            return List.of();
        }
        return parseQuotedStrings(json.substring(arrayStart, arrayEnd + 1));
    }

    static List<String> readObjectStringFields(String json, String field) {
        List<String> values = new ArrayList<>();
        String needle = '"' + field + '"';
        int from = 0;
        while (true) {
            int fieldIdx = json.indexOf(needle, from);
            if (fieldIdx < 0) {
                break;
            }
            int colon = json.indexOf(':', fieldIdx + needle.length());
            int valueStart = json.indexOf('"', colon + 1);
            if (colon < 0 || valueStart < 0) {
                break;
            }
            StringBuilder value = new StringBuilder();
            int i = valueStart + 1;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(++i);
                    value.append(switch (next) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case '"' -> '"';
                        case '\\' -> '\\';
                        default -> next;
                    });
                    i++;
                    continue;
                }
                if (c == '"') {
                    break;
                }
                value.append(c);
                i++;
            }
            values.add(value.toString());
            from = valueStart + 1;
        }
        return values;
    }

    private static List<String> parseQuotedStrings(String arrayJson) {
        List<String> values = new ArrayList<>();
        Matcher matcher = STRING_ARRAY.matcher(arrayJson);
        while (matcher.find()) {
            values.add(unescapeJson(matcher.group(1)));
        }
        return values;
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
