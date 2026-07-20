package com.company.loopengine.execution;

import com.company.loopengine.defect.attachment.AttachmentInspector;
import com.company.loopengine.defect.domain.DefectState;
import com.company.loopengine.defect.domain.DefectStateMachine;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AttemptService {
    static final Duration TOKEN_LEASE = Duration.ofMinutes(15);
    static final int MAX_BATCH_SIZE = 100;
    static final int MAX_PAYLOAD_BYTES = 256 * 1024;
    private static final Pattern EVENT_TYPE = Pattern.compile("^[a-z]+([.-][a-z]+)+$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final String gitlabBaseUrl;
    private final String gitlabToken;
    private final HttpClient http;
    private final DefectStateMachine stateMachine = new DefectStateMachine();

    @Autowired
    public AttemptService(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            JsonMapper jsonMapper,
            @Value("${loop.gitlab.api-url:http://gitlab.example}") String gitlabBaseUrl,
            @Value("${loop.gitlab.token:}") String gitlabToken) {
        this(jdbc, transactionManager, jsonMapper, gitlabBaseUrl, gitlabToken, Clock.systemUTC());
    }

    AttemptService(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            JsonMapper jsonMapper,
            String gitlabBaseUrl,
            String gitlabToken,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.jsonMapper = jsonMapper;
        this.gitlabBaseUrl = trimTrailingSlash(gitlabBaseUrl);
        this.gitlabToken = gitlabToken == null ? "" : gitlabToken;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public BootstrapResult bootstrap(
            UUID authenticatedNodeId, BootstrapRequest request, String callbackBaseUrl) {
        Objects.requireNonNull(authenticatedNodeId, "authenticatedNodeId");
        Objects.requireNonNull(request, "request");
        return transactions.execute(status -> bootstrapInTransaction(authenticatedNodeId, request, callbackBaseUrl));
    }

    public long appendEvents(String rawToken, UUID attemptId, List<EventInput> events) {
        Objects.requireNonNull(rawToken, "rawToken");
        Objects.requireNonNull(attemptId, "attemptId");
        if (events == null || events.isEmpty()) {
            throw new InvalidRequestException("events must not be empty");
        }
        if (events.size() > MAX_BATCH_SIZE) {
            throw new InvalidRequestException("event batch exceeds 100 events");
        }
        return transactions.execute(status -> appendEventsInTransaction(rawToken, attemptId, events));
    }

    public AttachmentContent openAttachment(String rawToken, UUID attemptId, UUID attachmentId) {
        AttemptRow attempt = requireAttemptByToken(rawToken, attemptId);
        ensureLeaseActive(attempt);
        AttachmentRow attachment = jdbc.sql("""
            select a.id, a.defect_id, a.source_url, a.name, a.content_type, a.size_bytes, a.sha256
            from defect_attachment a
            join repair_task t on t.defect_id = a.defect_id
            where a.id = :attachmentId and t.id = :taskId
            """)
            .param("attachmentId", attachmentId)
            .param("taskId", attempt.taskId())
            .query((rs, rowNum) -> new AttachmentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("defect_id", UUID.class),
                rs.getString("source_url"),
                rs.getString("name"),
                rs.getString("content_type"),
                rs.getObject("size_bytes") == null ? null : rs.getLong("size_bytes"),
                rs.getString("sha256")))
            .optional()
            .orElseThrow(() -> new InvalidRequestException("unknown attachment"));
        if (attachment.sizeBytes() == null
            || attachment.sha256() == null
            || attachment.sha256().isBlank()) {
            throw new InvalidRequestException("attachment metadata incomplete");
        }
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("loop-attempt-attachment-", ".bin");
            long size = fetchAttachmentToTempFile(attachment.sourceUrl(), tempFile);
            if (size > AttachmentInspector.MAX_ATTACHMENT_BYTES) {
                throw new InvalidRequestException("attachment exceeds 20 MiB");
            }
            if (size != attachment.sizeBytes()) {
                throw new AttachmentChangedException("ATTACHMENT_CHANGED");
            }
            String digest = sha256File(tempFile);
            if (!digest.equalsIgnoreCase(attachment.sha256())) {
                throw new AttachmentChangedException("ATTACHMENT_CHANGED");
            }
            byte[] body = Files.readAllBytes(tempFile);
            String contentType = attachment.contentType() == null || attachment.contentType().isBlank()
                ? "application/octet-stream"
                : attachment.contentType();
            return new AttachmentContent(contentType, attachment.name(), body);
        } catch (IOException ex) {
            throw new IllegalStateException("attachment staging failed", ex);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // best-effort cleanup of the bounded staging file
                }
            }
        }
    }

    private BootstrapResult bootstrapInTransaction(
            UUID authenticatedNodeId, BootstrapRequest request, String callbackBaseUrl) {
        ReservationRow reservation = jdbc.sql("""
            select id, task_id, node_id, pipeline_id, expires_at, state
            from task_reservation
            where id = :id
            for update
            """)
            .param("id", request.reservationId())
            .query((rs, rowNum) -> new ReservationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getObject("node_id", UUID.class),
                rs.getObject("pipeline_id") == null ? null : rs.getLong("pipeline_id"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getString("state")))
            .optional()
            .orElseThrow(() -> new BootstrapException("reservation not found"));

        if (!"ACTIVE".equals(reservation.state())) {
            throw new BootstrapException("reservation is not active");
        }
        if (!reservation.expiresAt().isAfter(clock.instant())) {
            throw new BootstrapException("reservation expired");
        }
        if (!reservation.nodeId().equals(authenticatedNodeId)) {
            throw new BootstrapException("certificate does not match reserved node");
        }
        if (!reservation.taskId().equals(request.taskId())) {
            throw new BootstrapException("task does not match reservation");
        }
        if (reservation.pipelineId() == null || reservation.pipelineId() != request.pipelineId()) {
            throw new BootstrapException("pipeline id does not match reservation");
        }

        TaskRow task = jdbc.sql("""
            select t.id, t.defect_id, t.project_key, t.profile_revision, t.profile_snapshot_json::text,
                   t.base_sha, t.state,
                   d.intake_project_id, d.issue_iid, d.issue_url, d.title, d.description, d.state as defect_state
            from repair_task t
            join defect d on d.id = t.defect_id
            where t.id = :id
            for update
            """)
            .param("id", request.taskId())
            .query((rs, rowNum) -> new TaskRow(
                rs.getObject("id", UUID.class),
                rs.getObject("defect_id", UUID.class),
                rs.getString("project_key"),
                rs.getLong("profile_revision"),
                rs.getString("profile_snapshot_json"),
                rs.getString("base_sha"),
                rs.getString("state"),
                rs.getLong("intake_project_id"),
                rs.getLong("issue_iid"),
                rs.getString("issue_url"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("defect_state")))
            .optional()
            .orElseThrow(() -> new BootstrapException("task not found"));

        if (!"RESERVED".equals(task.state())) {
            throw new BootstrapException("task is not reserved");
        }

        Optional<UUID> existingAttempt = jdbc.sql("select id from repair_attempt where job_id = :jobId")
            .param("jobId", request.jobId())
            .query(UUID.class)
            .optional();
        if (existingAttempt.isPresent()) {
            throw new BootstrapException("attempt already exists for job");
        }

        int attemptNo = jdbc.sql("""
            select coalesce(max(attempt_no), 0) + 1 from repair_attempt where task_id = :taskId
            """)
            .param("taskId", task.id())
            .query(Integer.class)
            .single();

        UUID attemptId = UUID.randomUUID();
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String tokenHash = sha256Hex(rawToken.getBytes(StandardCharsets.UTF_8));
        Instant startedAt = clock.instant();
        Duration jobTimeout = jobTimeout(task.profileSnapshotJson());
        Instant leaseExpiresAt = min(startedAt.plus(TOKEN_LEASE), startedAt.plus(jobTimeout));

        jdbc.sql("""
            insert into repair_attempt(
              id, task_id, attempt_no, node_id, reservation_id, pipeline_id, job_id, state,
              task_token_hash, lease_expires_at, started_at)
            values (
              :id, :taskId, :attemptNo, :nodeId, :reservationId, :pipelineId, :jobId, 'RUNNING',
              :tokenHash, :leaseExpiresAt, :startedAt)
            """)
            .param("id", attemptId)
            .param("taskId", task.id())
            .param("attemptNo", attemptNo)
            .param("nodeId", authenticatedNodeId)
            .param("reservationId", reservation.id())
            .param("pipelineId", request.pipelineId())
            .param("jobId", request.jobId())
            .param("tokenHash", tokenHash)
            .param("leaseExpiresAt", Timestamp.from(leaseExpiresAt))
            .param("startedAt", Timestamp.from(startedAt))
            .update();

        jdbc.sql("update repair_task set state = 'RUNNING', updated_at = :now where id = :id")
            .param("now", Timestamp.from(startedAt))
            .param("id", task.id())
            .update();

        DefectState defectState = DefectState.valueOf(task.defectState());
        stateMachine.requireMove(defectState, DefectState.RUNNING);
        jdbc.sql("update defect set state = 'RUNNING', updated_at = :now where id = :id")
            .param("now", Timestamp.from(startedAt))
            .param("id", task.defectId())
            .update();

        jdbc.sql("update task_reservation set state = 'CONSUMED' where id = :id")
            .param("id", reservation.id())
            .update();

        JsonNode taskPackage = buildTaskPackage(
            task, attemptId, authenticatedNodeId, callbackBaseUrl);
        return new BootstrapResult(attemptId, rawToken, taskPackage);
    }

    private long appendEventsInTransaction(String rawToken, UUID attemptId, List<EventInput> events) {
        AttemptRow attempt = requireAttemptForUpdate(rawToken, attemptId);
        ensureLeaseActive(attempt);
        List<EventInput> sorted = events.stream()
            .sorted(Comparator.comparingLong(EventInput::seq))
            .toList();
        long ack = contiguousAck(attemptId);
        for (EventInput event : sorted) {
            validateEvent(attempt, event);
            if (event.seq() <= ack) {
                insertEventIfAbsent(attemptId, event);
                continue;
            }
            if (event.seq() != ack + 1) {
                throw new InvalidRequestException("sequence gap beyond contiguous acknowledgment");
            }
            insertEventIfAbsent(attemptId, event);
            ack = event.seq();
        }
        Duration jobTimeout = jobTimeoutForAttempt(attemptId);
        Instant extended = min(clock.instant().plus(TOKEN_LEASE), attempt.startedAt().plus(jobTimeout));
        jdbc.sql("update repair_attempt set lease_expires_at = :lease where id = :id")
            .param("lease", Timestamp.from(extended))
            .param("id", attemptId)
            .update();
        return ack;
    }

    private void validateEvent(AttemptRow attempt, EventInput event) {
        if (!event.taskId().equals(attempt.taskId())) {
            throw new InvalidRequestException("taskId mismatch");
        }
        if (!event.attemptId().equals(attempt.id())) {
            throw new InvalidRequestException("attemptId mismatch");
        }
        if (!event.nodeId().equals(attempt.nodeId())) {
            throw new InvalidRequestException("nodeId mismatch");
        }
        if (event.seq() < 1) {
            throw new InvalidRequestException("seq must be positive");
        }
        if (!EVENT_TYPE.matcher(event.type()).matches()) {
            throw new InvalidRequestException("invalid event type");
        }
        byte[] payloadBytes = jsonMapper.writeValueAsBytes(event.payload());
        if (payloadBytes.length > MAX_PAYLOAD_BYTES) {
            throw new InvalidRequestException("payload exceeds 256 KiB");
        }
    }

    private void insertEventIfAbsent(UUID attemptId, EventInput event) {
        jdbc.sql("""
            insert into task_event(attempt_id, seq, event_time, type, payload_json)
            values (:attemptId, :seq, :eventTime, :type, cast(:payload as jsonb))
            on conflict (attempt_id, seq) do nothing
            """)
            .param("attemptId", attemptId)
            .param("seq", event.seq())
            .param("eventTime", Timestamp.from(event.time()))
            .param("type", event.type())
            .param("payload", jsonMapper.writeValueAsString(event.payload()))
            .update();
    }

    private long contiguousAck(UUID attemptId) {
        List<Long> sequences = jdbc.sql("""
            select seq from task_event where attempt_id = :attemptId order by seq asc
            """)
            .param("attemptId", attemptId)
            .query(Long.class)
            .list();
        long ack = 0L;
        for (long seq : sequences) {
            if (seq == ack + 1) {
                ack = seq;
            } else {
                break;
            }
        }
        return ack;
    }

    private AttemptRow requireAttemptByToken(String rawToken, UUID attemptId) {
        String tokenHash = sha256Hex(rawToken.getBytes(StandardCharsets.UTF_8));
        return jdbc.sql("""
            select id, task_id, node_id, lease_expires_at, started_at
            from repair_attempt
            where id = :id and task_token_hash = :tokenHash
            """)
            .param("id", attemptId)
            .param("tokenHash", tokenHash)
            .query(this::mapAttemptRow)
            .optional()
            .orElseThrow(() -> new TokenException("invalid task token"));
    }

    private AttemptRow requireAttemptForUpdate(String rawToken, UUID attemptId) {
        String tokenHash = sha256Hex(rawToken.getBytes(StandardCharsets.UTF_8));
        return jdbc.sql("""
            select id, task_id, node_id, lease_expires_at, started_at
            from repair_attempt
            where id = :id and task_token_hash = :tokenHash
            for update
            """)
            .param("id", attemptId)
            .param("tokenHash", tokenHash)
            .query(this::mapAttemptRow)
            .optional()
            .orElseThrow(() -> new TokenException("invalid task token"));
    }

    private AttemptRow mapAttemptRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AttemptRow(
            rs.getObject("id", UUID.class),
            rs.getObject("task_id", UUID.class),
            rs.getObject("node_id", UUID.class),
            rs.getTimestamp("lease_expires_at").toInstant(),
            rs.getTimestamp("started_at").toInstant());
    }

    private void ensureLeaseActive(AttemptRow attempt) {
        if (!attempt.leaseExpiresAt().isAfter(clock.instant())) {
            throw new TokenException("task token lease expired");
        }
    }

    private JsonNode buildTaskPackage(
            TaskRow task, UUID attemptId, UUID nodeId, String callbackBaseUrl) {
        JsonNode profile = jsonMapper.readTree(task.profileSnapshotJson());
        String repository = profile.path("repository").asString("group/" + task.projectKey());
        String module = firstModule(profile);
        String targetBranch = profile.path("defaultBranch").asString("main");
        String repositoryUrl = gitlabBaseUrl + "/" + repository + ".git";

        List<AttachmentRow> attachments = jdbc.sql("""
            select id, name, content_type, sha256
            from defect_attachment
            where defect_id = :defectId and sha256 is not null
            order by name asc
            """)
            .param("defectId", task.defectId())
            .query((rs, rowNum) -> new AttachmentRow(
                rs.getObject("id", UUID.class),
                null,
                null,
                rs.getString("name"),
                rs.getString("content_type"),
                null,
                rs.getString("sha256")))
            .list();

        ObjectNode root = jsonMapper.createObjectNode();
        root.put("protocol", "v1");
        root.put("taskId", task.id().toString());
        root.put("attemptId", attemptId.toString());
        root.put("nodeId", nodeId.toString());
        root.put("baseSha", task.baseSha());

        ObjectNode project = root.putObject("project");
        project.put("key", task.projectKey());
        project.put("repositoryUrl", repositoryUrl);
        project.put("module", module);
        project.put("targetBranch", targetBranch);
        project.put("profileRevision", task.profileRevision());

        ObjectNode issue = root.putObject("issue");
        issue.put("projectId", task.intakeProjectId());
        issue.put("iid", task.issueIid());
        issue.put("url", task.issueUrl());
        issue.put("title", task.title());
        issue.put("description", task.description());
        ArrayNode attachmentNodes = issue.putArray("attachments");
        String proxyBase = trimTrailingSlash(callbackBaseUrl);
        for (AttachmentRow attachment : attachments) {
            ObjectNode item = attachmentNodes.addObject();
            item.put("name", attachment.name());
            item.put("contentType", attachment.contentType() == null ? "application/octet-stream" : attachment.contentType());
            item.put("url", proxyBase + "/api/node/v1/attempts/" + attemptId + "/attachments/" + attachment.id());
            item.put("sha256", attachment.sha256());
        }

        ArrayNode validation = root.putArray("validation");
        JsonNode commands = profile.path("validationCommands");
        if (commands.isArray()) {
            Iterator<JsonNode> it = commands.iterator();
            while (it.hasNext()) {
                JsonNode command = it.next();
                ObjectNode entry = validation.addObject();
                entry.put("program", command.path("program").asString());
                ArrayNode args = entry.putArray("args");
                JsonNode argNode = command.path("args");
                if (argNode.isArray()) {
                    argNode.forEach(arg -> args.add(arg.asString()));
                }
                entry.put("timeoutSeconds", command.path("timeoutSeconds").asInt(600));
                entry.put("required", command.path("required").asBoolean(true));
            }
        }
        if (validation.isEmpty()) {
            ObjectNode fallback = validation.addObject();
            fallback.put("program", "true");
            fallback.putArray("args");
            fallback.put("timeoutSeconds", 60);
            fallback.put("required", true);
        }

        ObjectNode callback = root.putObject("callback");
        callback.put("baseUrl", proxyBase);
        callback.put("eventsPath", "/api/node/v1/attempts/" + attemptId + "/events:batch");
        return root;
    }

    private static String firstModule(JsonNode profile) {
        JsonNode modules = profile.path("modules");
        if (modules.isArray() && modules.size() > 0) {
            return modules.get(0).asString(".");
        }
        return ".";
    }

    private Duration jobTimeoutForAttempt(UUID attemptId) {
        String profileJson = jdbc.sql("""
            select t.profile_snapshot_json::text
            from repair_attempt a
            join repair_task t on t.id = a.task_id
            where a.id = :id
            """)
            .param("id", attemptId)
            .query(String.class)
            .single();
        return jobTimeout(profileJson);
    }

    private Duration jobTimeout(String profileJson) {
        try {
            JsonNode profile = jsonMapper.readTree(profileJson);
            int maxSeconds = 0;
            JsonNode commands = profile.path("validationCommands");
            if (commands.isArray()) {
                Iterator<JsonNode> it = commands.iterator();
                while (it.hasNext()) {
                    JsonNode command = it.next();
                    maxSeconds = Math.max(maxSeconds, command.path("timeoutSeconds").asInt(0));
                }
            }
            if (maxSeconds <= 0) {
                return Duration.ofHours(2);
            }
            return Duration.ofSeconds(maxSeconds);
        } catch (RuntimeException ex) {
            return Duration.ofHours(2);
        }
    }

    private long fetchAttachmentToTempFile(String sourceUrl, Path tempFile) {
        try {
            URI initial = URI.create(sourceUrl);
            URI current = initial;
            for (int hop = 0; hop < 5; hop++) {
                HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(30))
                    .header("PRIVATE-TOKEN", gitlabToken)
                    .GET()
                    .build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("location").orElseThrow(
                        () -> new InvalidRequestException("redirect without location"));
                    URI next = current.resolve(location);
                    if (!initial.getHost().equalsIgnoreCase(next.getHost())) {
                        throw new InvalidRequestException("redirect to another host is not allowed");
                    }
                    current = next;
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new InvalidRequestException("unable to fetch attachment");
                }
                try (InputStream body = response.body()) {
                    return writeBounded(body, tempFile, AttachmentInspector.MAX_ATTACHMENT_BYTES);
                }
            }
            throw new InvalidRequestException("too many redirects");
        } catch (InvalidRequestException | AttachmentChangedException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("attachment fetch interrupted", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("attachment fetch failed", ex);
        }
    }

    private static long writeBounded(InputStream body, Path tempFile, long maxBytes) throws IOException {
        long total = 0L;
        byte[] buffer = new byte[8192];
        try (OutputStream out = Files.newOutputStream(tempFile)) {
            int read;
            while ((read = body.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new InvalidRequestException("attachment exceeds 20 MiB");
                }
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    private static String sha256File(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String sha256Hex(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record BootstrapRequest(UUID taskId, UUID reservationId, long pipelineId, long jobId) {}

    public record BootstrapResult(UUID attemptId, String taskToken, JsonNode taskPackage) {}

    public record EventInput(
        UUID taskId, UUID attemptId, UUID nodeId, long seq, Instant time, String type, JsonNode payload) {}

    public record AttachmentContent(String contentType, String filename, byte[] body) {}

    public static final class BootstrapException extends RuntimeException {
        public BootstrapException(String message) {
            super(message);
        }
    }

    public static final class TokenException extends RuntimeException {
        public TokenException(String message) {
            super(message);
        }
    }

    public static final class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }

    public static final class AttachmentChangedException extends RuntimeException {
        public AttachmentChangedException(String message) {
            super(message);
        }
    }

    private record ReservationRow(
        UUID id, UUID taskId, UUID nodeId, Long pipelineId, Instant expiresAt, String state) {}

    private record TaskRow(
        UUID id,
        UUID defectId,
        String projectKey,
        long profileRevision,
        String profileSnapshotJson,
        String baseSha,
        String state,
        long intakeProjectId,
        long issueIid,
        String issueUrl,
        String title,
        String description,
        String defectState) {}

    private record AttemptRow(
        UUID id, UUID taskId, UUID nodeId, Instant leaseExpiresAt, Instant startedAt) {}

    private record AttachmentRow(
        UUID id,
        UUID defectId,
        String sourceUrl,
        String name,
        String contentType,
        Long sizeBytes,
        String sha256) {}
}
