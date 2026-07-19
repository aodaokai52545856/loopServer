package com.company.loopengine.gitlab.runner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Admin-token client for creating locked-down project Runners bound to the central repair project.
 */
public final class RunnerAdminClient {
    private static final Pattern ID_FIELD = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern TOKEN_FIELD = Pattern.compile("\"token\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"])*)\"");

    private final String baseUrl;
    private final String token;
    private final long centralProjectId;
    private final HttpClient http;

    public RunnerAdminClient(String baseUrl, String token, long centralProjectId) {
        this(baseUrl, token, centralProjectId, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build());
    }

    RunnerAdminClient(String baseUrl, String token, long centralProjectId, HttpClient http) {
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.token = Objects.requireNonNull(token, "token");
        this.centralProjectId = centralProjectId;
        this.http = Objects.requireNonNull(http, "http");
    }

    public CreatedRunner createProjectRunner(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        String tag = "repair-node-" + nodeId;
        String description = "loop-engine:" + nodeId;
        String body = "{\"runner_type\":\"project_type\""
            + ",\"project_id\":" + centralProjectId
            + ",\"description\":\"" + escapeJson(description) + "\""
            + ",\"tag_list\":[\"" + escapeJson(tag) + "\"]"
            + ",\"run_untagged\":false"
            + ",\"locked\":true"
            + ",\"access_level\":\"not_protected\"}";
        String response = send("POST", "/api/v4/user/runners", body);
        long runnerId = readLongField(response);
        String authenticationToken = readTokenField(response);
        if (authenticationToken == null || authenticationToken.isBlank()) {
            throw new RunnerAdminTerminalException("GitLab runner response missing token");
        }
        return new CreatedRunner(runnerId, authenticationToken);
    }

    public void verifyRunner(long runnerId) {
        send("GET", "/api/v4/runners/" + runnerId, null);
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
                throw new RunnerAdminTerminalException("GitLab HTTP " + status);
            }
            if (status == 429 || status >= 500) {
                throw new RunnerAdminRetryableException("GitLab HTTP " + status);
            }
            if (status < 200 || status >= 300) {
                throw new RunnerAdminTerminalException("GitLab HTTP " + status);
            }
            return response.body() == null ? "" : response.body();
        } catch (RunnerAdminException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RunnerAdminRetryableException("GitLab transport failure: " + ex.getMessage());
        }
    }

    private static long readLongField(String json) {
        Matcher matcher = ID_FIELD.matcher(json);
        if (!matcher.find()) {
            throw new RunnerAdminTerminalException("GitLab runner response missing id");
        }
        return Long.parseLong(matcher.group(1));
    }

    private static String readTokenField(String json) {
        Matcher matcher = TOKEN_FIELD.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return unescapeJson(matcher.group(1));
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

    public record CreatedRunner(long runnerId, String authenticationToken) {}

    public static sealed class RunnerAdminException extends RuntimeException
            permits RunnerAdminRetryableException, RunnerAdminTerminalException {
        public RunnerAdminException(String message) {
            super(message);
        }
    }

    public static final class RunnerAdminRetryableException extends RunnerAdminException {
        public RunnerAdminRetryableException(String message) {
            super(message);
        }
    }

    public static final class RunnerAdminTerminalException extends RunnerAdminException {
        public RunnerAdminTerminalException(String message) {
            super(message);
        }
    }
}
