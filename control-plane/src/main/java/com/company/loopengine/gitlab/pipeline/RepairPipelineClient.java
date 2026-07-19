package com.company.loopengine.gitlab.pipeline;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Triggers and inspects directed repair Pipelines on the central GitLab project.
 */
public final class RepairPipelineClient {
    private static final Pattern ID_FIELD = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern STATUS_FIELD = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern OBJECT_BLOCK = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern KEY_VALUE =
        Pattern.compile("\"key\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"])*)\"\\s*,\\s*\"value\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"])*)\"");
    private static final Pattern VALUE_KEY =
        Pattern.compile("\"value\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"])*)\"\\s*,\\s*\"key\"\\s*:\\s*\"((?:\\\\.|[^\\\\\"])*)\"");

    private final String baseUrl;
    private final String token;
    private final long centralProjectId;
    private final String ref;
    private final HttpClient http;

    public RepairPipelineClient(String baseUrl, String token, long centralProjectId) {
        this(baseUrl, token, centralProjectId, "main", HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build());
    }

    RepairPipelineClient(String baseUrl, String token, long centralProjectId, String ref, HttpClient http) {
        this.baseUrl = trimTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.token = Objects.requireNonNull(token, "token");
        this.centralProjectId = centralProjectId;
        this.ref = Objects.requireNonNull(ref, "ref");
        this.http = Objects.requireNonNull(http, "http");
    }

    public long triggerRepairPipeline(UUID taskId, UUID reservationId, UUID nodeId, String runnerTag) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(runnerTag, "runnerTag");
        String body = "{\"ref\":\"" + escapeJson(ref) + "\",\"variables\":["
            + variable("LOOP_TASK_ID", taskId.toString()) + ","
            + variable("LOOP_RESERVATION_ID", reservationId.toString()) + ","
            + variable("LOOP_NODE_ID", nodeId.toString()) + ","
            + variable("LOOP_RUNNER_TAG", runnerTag)
            + "]}";
        String response = send("POST", projectPath() + "/pipeline", body);
        return readLongField(response, "pipeline id");
    }

    public List<PipelineSummary> listRecentPipelines(int perPage) {
        int pageSize = Math.max(1, Math.min(perPage, 100));
        String response = send("GET", projectPath() + "/pipelines?per_page=" + pageSize + "&order_by=id&sort=desc", null);
        List<PipelineSummary> pipelines = new ArrayList<>();
        Matcher objects = OBJECT_BLOCK.matcher(response);
        while (objects.find()) {
            String block = objects.group();
            Matcher idMatcher = ID_FIELD.matcher(block);
            Matcher statusMatcher = STATUS_FIELD.matcher(block);
            if (idMatcher.find() && statusMatcher.find()) {
                pipelines.add(new PipelineSummary(Long.parseLong(idMatcher.group(1)), statusMatcher.group(1)));
            }
        }
        return List.copyOf(pipelines);
    }

    public Map<String, String> getPipelineVariables(long pipelineId) {
        String response = send("GET", projectPath() + "/pipelines/" + pipelineId + "/variables", null);
        Map<String, String> variables = new LinkedHashMap<>();
        Matcher kv = KEY_VALUE.matcher(response);
        while (kv.find()) {
            variables.put(unescapeJson(kv.group(1)), unescapeJson(kv.group(2)));
        }
        Matcher vk = VALUE_KEY.matcher(response);
        while (vk.find()) {
            variables.putIfAbsent(unescapeJson(vk.group(2)), unescapeJson(vk.group(1)));
        }
        return Map.copyOf(variables);
    }

    public PipelineSummary getPipeline(long pipelineId) {
        String response = send("GET", projectPath() + "/pipelines/" + pipelineId, null);
        Matcher statusMatcher = STATUS_FIELD.matcher(response);
        if (!statusMatcher.find()) {
            throw new PipelineTerminalException("GitLab pipeline response missing status");
        }
        return new PipelineSummary(pipelineId, statusMatcher.group(1));
    }

    public List<JobSummary> listJobs(long pipelineId) {
        String response = send("GET", projectPath() + "/pipelines/" + pipelineId + "/jobs", null);
        List<JobSummary> jobs = new ArrayList<>();
        Matcher objects = OBJECT_BLOCK.matcher(response);
        while (objects.find()) {
            String block = objects.group();
            Matcher idMatcher = ID_FIELD.matcher(block);
            Matcher statusMatcher = STATUS_FIELD.matcher(block);
            if (idMatcher.find() && statusMatcher.find()) {
                jobs.add(new JobSummary(Long.parseLong(idMatcher.group(1)), statusMatcher.group(1)));
            }
        }
        return List.copyOf(jobs);
    }

    public void cancelPipeline(long pipelineId) {
        send("POST", projectPath() + "/pipelines/" + pipelineId + "/cancel", null);
    }

    private String projectPath() {
        return "/api/v4/projects/" + centralProjectId;
    }

    private static String variable(String key, String value) {
        return "{\"key\":\"" + escapeJson(key) + "\",\"value\":\"" + escapeJson(value) + "\"}";
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
            if (status == 429 || status >= 500) {
                throw new PipelineAmbiguousException("GitLab HTTP " + status);
            }
            if (status == 401 || status == 403 || status == 404
                    || (status >= 400 && status < 500)) {
                throw new PipelineTerminalException("GitLab HTTP " + status);
            }
            if (status < 200 || status >= 300) {
                throw new PipelineTerminalException("GitLab HTTP " + status);
            }
            return response.body() == null ? "" : response.body();
        } catch (PipelineException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PipelineAmbiguousException("GitLab transport failure: " + ex.getMessage());
        }
    }

    private static long readLongField(String json, String label) {
        Matcher matcher = ID_FIELD.matcher(json);
        if (!matcher.find()) {
            throw new PipelineTerminalException("GitLab response missing " + label);
        }
        return Long.parseLong(matcher.group(1));
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

    public record PipelineSummary(long id, String status) {}

    public record JobSummary(long id, String status) {}

    public static sealed class PipelineException extends RuntimeException
            permits PipelineTerminalException, PipelineAmbiguousException {
        public PipelineException(String message) {
            super(message);
        }
    }

    /** Definite client/content rejection (4xx). */
    public static final class PipelineTerminalException extends PipelineException {
        public PipelineTerminalException(String message) {
            super(message);
        }
    }

    /** Timeout, reset, 429 or 5xx — trigger outcome unknown. */
    public static final class PipelineAmbiguousException extends PipelineException {
        public PipelineAmbiguousException(String message) {
            super(message);
        }
    }
}
