package com.company.loopengine.query;

import com.company.loopengine.query.DashboardQueryController.SignedCursor;
import com.company.loopengine.query.DashboardQueryController.Viewer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/nodes")
public class NodeQueryController {
    private final JdbcClient jdbc;
    private final SignedCursor cursors;

    public NodeQueryController(
            JdbcClient jdbc, @Value("${loop.query.cursor-hmac-secret:dev-cursor-secret}") String secret) {
        this.jdbc = jdbc;
        this.cursors = new SignedCursor(secret);
    }

    @GetMapping
    Map<String, Object> list(
            Authentication authentication,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        Viewer viewer = Viewer.from(authentication);
        int pageSize = DashboardQueryController.clampLimit(limit);
        Optional<SignedCursor.Key> after = cursors.decode(cursor);

        StringBuilder sql = new StringBuilder("""
                select n.id, n.name, n.owner_id, n.description, n.state, n.enabled,
                       n.concurrency_limit, n.active_slots, n.desired_revision, n.applied_revision,
                       n.allowed_projects_json::text as allowed_projects_json,
                       n.capabilities_json::text as capabilities_json,
                       n.last_heartbeat_at, n.created_at, n.updated_at
                from repair_node n
                where """);
        sql.append(viewer.admin() ? "true" : Viewer.NODE_VISIBLE_SQL);
        if (state != null && !state.isBlank()) {
            sql.append(" and n.state = :state");
        }
        if (owner != null && !owner.isBlank()) {
            sql.append(" and n.owner_id = :owner");
        }
        if (after.isPresent()) {
            sql.append("""
                     and (n.created_at < :cursorTs
                          or (n.created_at = :cursorTs and n.id < :cursorId))
                    """);
        }
        sql.append(" order by n.created_at desc, n.id desc limit :limit");

        var query = jdbc.sql(sql.toString()).param("limit", pageSize + 1);
        if (!viewer.admin()) {
            query = query.param("viewerId", viewer.gitlabUserId()).param("viewerName", viewer.name());
        }
        if (state != null && !state.isBlank()) {
            query = query.param("state", state);
        }
        if (owner != null && !owner.isBlank()) {
            query = query.param("owner", owner);
        }
        if (after.isPresent()) {
            query = query.param("cursorTs", Timestamp.from(after.get().timestamp()))
                    .param("cursorId", after.get().id());
        }

        List<Map<String, Object>> rows = query.query((rs, rowNum) -> mapNode(rs)).list();
        return DashboardQueryController.page(rows, pageSize, r -> {
            Instant ts = Instant.parse((String) r.get("createdAt"));
            UUID id = UUID.fromString((String) r.get("id"));
            return cursors.encode(new SignedCursor.Key(ts, id));
        });
    }

    @GetMapping("/{nodeId}")
    Map<String, Object> detail(Authentication authentication, @PathVariable String nodeId) {
        Viewer viewer = Viewer.from(authentication);
        StringBuilder sql = new StringBuilder("""
                select n.id, n.name, n.owner_id, n.description, n.state, n.enabled,
                       n.concurrency_limit, n.active_slots, n.desired_revision, n.applied_revision,
                       n.allowed_projects_json::text as allowed_projects_json,
                       n.capabilities_json::text as capabilities_json,
                       n.last_heartbeat_at, n.created_at, n.updated_at
                from repair_node n
                where (n.name = :nodeId or cast(n.id as varchar) = :nodeId)
                """);
        if (!viewer.admin()) {
            sql.append(" and ").append(Viewer.NODE_VISIBLE_SQL);
        }
        var query = jdbc.sql(sql.toString()).param("nodeId", nodeId);
        if (!viewer.admin()) {
            query = query.param("viewerId", viewer.gitlabUserId()).param("viewerName", viewer.name());
        }
        return query.query((rs, rowNum) -> mapNode(rs))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static Map<String, Object> mapNode(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getObject("id", UUID.class).toString());
        row.put("name", rs.getString("name"));
        row.put("ownerId", rs.getString("owner_id"));
        row.put("description", rs.getString("description"));
        row.put("state", rs.getString("state"));
        row.put("enabled", rs.getBoolean("enabled"));
        row.put("concurrencyLimit", rs.getInt("concurrency_limit"));
        row.put("activeSlots", rs.getInt("active_slots"));
        row.put("desiredRevision", rs.getLong("desired_revision"));
        row.put("appliedRevision", rs.getLong("applied_revision"));
        row.put("allowedProjectsJson", rs.getString("allowed_projects_json"));
        row.put("capabilitiesJson", rs.getString("capabilities_json"));
        Timestamp heartbeat = rs.getTimestamp("last_heartbeat_at");
        row.put("lastHeartbeatAt", heartbeat == null ? null : heartbeat.toInstant().toString());
        row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        row.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
        return row;
    }
}
