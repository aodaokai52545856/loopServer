package com.company.loopengine.query;

import com.company.loopengine.shared.api.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class DashboardQueryController {
    private final JdbcClient jdbc;
    private final SignedCursor cursors;

    public DashboardQueryController(
            JdbcClient jdbc, @Value("${loop.query.cursor-hmac-secret:dev-cursor-secret}") String secret) {
        this.jdbc = jdbc;
        this.cursors = new SignedCursor(secret);
    }

    SignedCursor cursors() {
        return cursors;
    }

    @GetMapping("/dashboard")
    Map<String, Object> dashboard(Authentication authentication) {
        Viewer viewer = Viewer.from(authentication);
        String visibility = viewer.admin() ? "" : " and t.project_key in (" + Viewer.VISIBLE_PROJECTS_SQL + ")";
        var sql = jdbc.sql("""
                select t.state as state, count(*)::bigint as cnt
                from repair_task t
                where 1=1
                """ + visibility + """
                group by t.state
                order by t.state
                """);
        if (!viewer.admin()) {
            sql = sql.param("viewerId", viewer.gitlabUserId());
        }
        List<Map<String, Object>> byState = sql.query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("state", rs.getString("state"));
                    row.put("count", rs.getLong("cnt"));
                    return row;
                })
                .list();

        var nodeSql = jdbc.sql("""
                select state, count(*)::bigint as cnt
                from repair_node n
                where """ + (viewer.admin() ? "true" : Viewer.NODE_VISIBLE_SQL) + """
                group by state
                order by state
                """);
        if (!viewer.admin()) {
            nodeSql = nodeSql.param("viewerId", viewer.gitlabUserId()).param("viewerName", viewer.name());
        }
        List<Map<String, Object>> nodesByState = nodeSql.query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("state", rs.getString("state"));
                    row.put("count", rs.getLong("cnt"));
                    return row;
                })
                .list();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tasksByState", byState);
        body.put("nodesByState", nodesByState);
        return body;
    }

    @GetMapping("/projects")
    Map<String, Object> projects(Authentication authentication) {
        Viewer viewer = Viewer.from(authentication);
        var sql = jdbc.sql("""
                select p.id, p.project_key, p.gitlab_path, p.active_revision, p.created_at
                from project_profile p
                where """ + (viewer.admin() ? "true" : "p.project_key in (" + Viewer.VISIBLE_PROJECTS_SQL + ")") + """
                order by p.created_at desc, p.id desc
                """);
        if (!viewer.admin()) {
            sql = sql.param("viewerId", viewer.gitlabUserId());
        }
        List<Map<String, Object>> items = sql.query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class).toString());
                    row.put("projectKey", rs.getString("project_key"));
                    row.put("gitlabPath", rs.getString("gitlab_path"));
                    row.put("activeRevision", rs.getObject("active_revision"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return row;
                })
                .list();
        return Map.of("items", items);
    }

    @GetMapping("/projects/{projectKey}/revisions")
    Map<String, Object> revisions(Authentication authentication, @PathVariable String projectKey) {
        Viewer viewer = Viewer.from(authentication);
        if (!viewer.canSeeProject(jdbc, projectKey)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        List<Map<String, Object>> items = jdbc.sql("""
                select r.revision, r.config_json::text as config_json, r.config_sha256,
                       r.published_by, r.published_at
                from project_profile_revision r
                join project_profile p on p.id = r.profile_id
                where p.project_key = :projectKey
                order by r.revision desc
                """)
                .param("projectKey", projectKey)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("revision", rs.getLong("revision"));
                    row.put("configJson", rs.getString("config_json"));
                    row.put("configSha256", rs.getString("config_sha256"));
                    row.put("publishedBy", rs.getString("published_by"));
                    row.put("publishedAt", rs.getTimestamp("published_at").toInstant().toString());
                    return row;
                })
                .list();
        return Map.of("items", items);
    }

    @GetMapping("/operations/webhooks")
    @PreAuthorize("hasRole('ADMIN')")
    Map<String, Object> webhooks(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        int pageSize = clampLimit(limit);
        Optional<SignedCursor.Key> after = cursors.decode(cursor);
        StringBuilder sql = new StringBuilder("""
                select event_uuid, event_name, received_at, processing_state, attempt_count,
                       next_attempt_at, processed_at, last_error
                from gitlab_webhook_delivery
                where 1=1
                """);
        if (state != null && !state.isBlank()) {
            sql.append(" and processing_state = :state");
        }
        if (after.isPresent()) {
            sql.append("""
                     and (received_at < :cursorTs
                          or (received_at = :cursorTs and event_uuid < :cursorId))
                    """);
        }
        sql.append(" order by received_at desc, event_uuid desc limit :limit");
        var query = jdbc.sql(sql.toString()).param("limit", pageSize + 1);
        if (state != null && !state.isBlank()) {
            query = query.param("state", state);
        }
        if (after.isPresent()) {
            query = query.param("cursorTs", Timestamp.from(after.get().timestamp()))
                    .param("cursorId", after.get().id().toString());
        }
        List<Map<String, Object>> rows = query.query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("eventUuid", rs.getString("event_uuid"));
                    row.put("eventName", rs.getString("event_name"));
                    row.put("receivedAt", rs.getTimestamp("received_at").toInstant().toString());
                    row.put("state", rs.getString("processing_state"));
                    row.put("attemptCount", rs.getInt("attempt_count"));
                    Timestamp next = rs.getTimestamp("next_attempt_at");
                    row.put("nextAttemptAt", next == null ? null : next.toInstant().toString());
                    Timestamp processed = rs.getTimestamp("processed_at");
                    row.put("processedAt", processed == null ? null : processed.toInstant().toString());
                    row.put("lastError", rs.getString("last_error"));
                    return row;
                })
                .list();
        return page(rows, pageSize, r -> {
            Instant ts = Instant.parse((String) r.get("receivedAt"));
            UUID id = uuidFromString((String) r.get("eventUuid"));
            return cursors.encode(new SignedCursor.Key(ts, id));
        });
    }

    @GetMapping("/operations/outbox")
    @PreAuthorize("hasRole('ADMIN')")
    Map<String, Object> outbox(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        int pageSize = clampLimit(limit);
        Optional<SignedCursor.Key> after = cursors.decode(cursor);
        StringBuilder sql = new StringBuilder("""
                select id, aggregate_type, aggregate_id, event_type, occurred_at, attempt_count,
                       processed_at, last_error,
                       case when processed_at is null then 'PENDING' else 'PROCESSED' end as state
                from outbox_event
                where 1=1
                """);
        if (state != null && !state.isBlank()) {
            if ("PENDING".equalsIgnoreCase(state)) {
                sql.append(" and processed_at is null");
            } else if ("PROCESSED".equalsIgnoreCase(state)) {
                sql.append(" and processed_at is not null");
            }
        }
        if (after.isPresent()) {
            sql.append("""
                     and (occurred_at < :cursorTs
                          or (occurred_at = :cursorTs and id < :cursorId))
                    """);
        }
        sql.append(" order by occurred_at desc, id desc limit :limit");
        var query = jdbc.sql(sql.toString()).param("limit", pageSize + 1);
        if (after.isPresent()) {
            query = query.param("cursorTs", Timestamp.from(after.get().timestamp()))
                    .param("cursorId", after.get().id());
        }
        List<Map<String, Object>> rows = query.query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class).toString());
                    row.put("aggregateType", rs.getString("aggregate_type"));
                    row.put("aggregateId", rs.getString("aggregate_id"));
                    row.put("eventType", rs.getString("event_type"));
                    row.put("occurredAt", rs.getTimestamp("occurred_at").toInstant().toString());
                    row.put("attemptCount", rs.getInt("attempt_count"));
                    Timestamp processed = rs.getTimestamp("processed_at");
                    row.put("processedAt", processed == null ? null : processed.toInstant().toString());
                    row.put("lastError", rs.getString("last_error"));
                    row.put("state", rs.getString("state"));
                    return row;
                })
                .list();
        return page(rows, pageSize, r -> {
            Instant ts = Instant.parse((String) r.get("occurredAt"));
            UUID id = UUID.fromString((String) r.get("id"));
            return cursors.encode(new SignedCursor.Key(ts, id));
        });
    }

    @GetMapping("/audit")
    @PreAuthorize("hasRole('ADMIN')")
    Map<String, Object> audit(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String object,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        int pageSize = clampLimit(limit);
        Optional<SignedCursor.Key> after = cursors.decode(cursor);
        StringBuilder sql = new StringBuilder("""
                select id, actor_type, actor_id, action, object_type, object_id, request_id,
                       detail_json::text as detail_json, created_at
                from audit_log
                where 1=1
                """);
        if (actor != null && !actor.isBlank()) {
            sql.append(" and actor_id = :actor");
        }
        if (object != null && !object.isBlank()) {
            sql.append(" and object_id = :object");
        }
        if (after.isPresent()) {
            sql.append("""
                     and (created_at < :cursorTs
                          or (created_at = :cursorTs and id < :cursorId))
                    """);
        }
        sql.append(" order by created_at desc, id desc limit :limit");
        var query = jdbc.sql(sql.toString()).param("limit", pageSize + 1);
        if (actor != null && !actor.isBlank()) {
            query = query.param("actor", actor);
        }
        if (object != null && !object.isBlank()) {
            query = query.param("object", object);
        }
        if (after.isPresent()) {
            query = query.param("cursorTs", Timestamp.from(after.get().timestamp()))
                    .param("cursorId", after.get().id().getLeastSignificantBits());
        }
        List<Map<String, Object>> rows = query.query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    long id = rs.getLong("id");
                    row.put("id", id);
                    row.put("actorType", rs.getString("actor_type"));
                    row.put("actorId", rs.getString("actor_id"));
                    row.put("action", rs.getString("action"));
                    row.put("objectType", rs.getString("object_type"));
                    row.put("objectId", rs.getString("object_id"));
                    row.put("requestId", rs.getString("request_id"));
                    row.put("detailJson", rs.getString("detail_json"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    row.put("cursorId", new UUID(0L, id).toString());
                    return row;
                })
                .list();
        return page(rows, pageSize, r -> {
            Instant ts = Instant.parse((String) r.get("createdAt"));
            UUID id = UUID.fromString((String) r.get("cursorId"));
            r.remove("cursorId");
            return cursors.encode(new SignedCursor.Key(ts, id));
        });
    }

    static int clampLimit(Integer limit) {
        if (limit == null) {
            return 20;
        }
        return Math.max(1, Math.min(100, limit));
    }

    static Map<String, Object> page(
            List<Map<String, Object>> rows, int pageSize, java.util.function.Function<Map<String, Object>, String> cursorFn) {
        boolean hasMore = rows.size() > pageSize;
        List<Map<String, Object>> items = hasMore ? new ArrayList<>(rows.subList(0, pageSize)) : rows;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        if (hasMore && !items.isEmpty()) {
            body.put("nextCursor", cursorFn.apply(items.get(items.size() - 1)));
        } else if (!items.isEmpty()) {
            body.put("nextCursor", cursorFn.apply(items.get(items.size() - 1)));
        } else {
            body.put("nextCursor", null);
        }
        return body;
    }

    private static UUID uuidFromString(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    public record Viewer(String name, boolean admin, Long gitlabUserId) {
        static final String VISIBLE_PROJECTS_SQL =
                """
                select p.project_key
                from project_profile p
                join project_profile_owner o on o.profile_id = p.id
                where o.gitlab_user_id = :viewerId
                """;

        static final String NODE_VISIBLE_SQL =
                """
                (
                  n.owner_id = :viewerName
                  or exists (
                    select 1
                    from jsonb_array_elements_text(n.allowed_projects_json) ap(project_key)
                    where ap.project_key in (
                """
                        + VISIBLE_PROJECTS_SQL
                        + """
                    )
                  )
                )
                """;

        public static Viewer from(Authentication authentication) {
            if (authentication == null || !authentication.isAuthenticated()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            }
            boolean admin = false;
            for (GrantedAuthority authority : authentication.getAuthorities()) {
                if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                    admin = true;
                    break;
                }
            }
            Long gitlabUserId = null;
            try {
                gitlabUserId = Long.parseLong(authentication.getName());
            } catch (NumberFormatException ignored) {
                // non-numeric principals have no project ownership rows
            }
            return new Viewer(authentication.getName(), admin, gitlabUserId);
        }

        public boolean canSeeProject(JdbcClient jdbc, String projectKey) {
            if (admin) {
                return true;
            }
            if (gitlabUserId == null) {
                return false;
            }
            Boolean ok = jdbc.sql("""
                    select exists(
                      select 1
                      from project_profile p
                      join project_profile_owner o on o.profile_id = p.id
                      where p.project_key = :projectKey
                        and o.gitlab_user_id = :viewerId
                    )
                    """)
                    .param("projectKey", projectKey)
                    .param("viewerId", gitlabUserId)
                    .query(Boolean.class)
                    .single();
            return Boolean.TRUE.equals(ok);
        }

        public boolean canSeeTask(JdbcClient jdbc, UUID taskId) {
            if (admin) {
                return jdbc.sql("select exists(select 1 from repair_task where id = :id)")
                        .param("id", taskId)
                        .query(Boolean.class)
                        .single();
            }
            if (gitlabUserId == null) {
                return false;
            }
            Boolean ok = jdbc.sql("""
                    select exists(
                      select 1
                      from repair_task t
                      where t.id = :id
                        and t.project_key in (
                    """
                            + VISIBLE_PROJECTS_SQL
                            + """
                        )
                    )
                    """)
                    .param("id", taskId)
                    .param("viewerId", gitlabUserId)
                    .query(Boolean.class)
                    .single();
            return Boolean.TRUE.equals(ok);
        }
    }

    public static final class SignedCursor {
        private final byte[] secret;

        SignedCursor(String secret) {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }

        public record Key(Instant timestamp, UUID id) {}

        public String encode(Key key) {
            String payload = "{\"t\":\"" + key.timestamp() + "\",\"id\":\"" + key.id() + "\"}";
            String body = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(body));
            return body + "." + sig;
        }

        public Optional<Key> decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return Optional.empty();
            }
            int dot = cursor.indexOf('.');
            if (dot <= 0 || dot == cursor.length() - 1) {
                throw new InvalidRequestException("Invalid cursor");
            }
            String body = cursor.substring(0, dot);
            String sig = cursor.substring(dot + 1);
            byte[] expected = hmac(body);
            byte[] actual;
            try {
                actual = Base64.getUrlDecoder().decode(sig);
            } catch (IllegalArgumentException ex) {
                throw new InvalidRequestException("Invalid cursor");
            }
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new InvalidRequestException("Invalid cursor");
            }
            try {
                String json = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
                int tIdx = json.indexOf("\"t\":\"");
                int idIdx = json.indexOf("\"id\":\"");
                if (tIdx < 0 || idIdx < 0) {
                    throw new InvalidRequestException("Invalid cursor");
                }
                int tStart = tIdx + 5;
                int tEnd = json.indexOf('"', tStart);
                int idStart = idIdx + 6;
                int idEnd = json.indexOf('"', idStart);
                Instant timestamp = Instant.parse(json.substring(tStart, tEnd));
                UUID id = UUID.fromString(json.substring(idStart, idEnd));
                return Optional.of(new Key(timestamp, id));
            } catch (RuntimeException ex) {
                throw new InvalidRequestException("Invalid cursor");
            }
        }

        private byte[] hmac(String body) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret, "HmacSHA256"));
                return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
