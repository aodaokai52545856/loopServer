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
@RequestMapping("/api/tasks")
public class TaskQueryController {
    private final JdbcClient jdbc;
    private final SignedCursor cursors;

    public TaskQueryController(
            JdbcClient jdbc, @Value("${loop.query.cursor-hmac-secret:dev-cursor-secret}") String secret) {
        this.jdbc = jdbc;
        this.cursors = new SignedCursor(secret);
    }

    @GetMapping
    Map<String, Object> list(
            Authentication authentication,
            @RequestParam(required = false) String projectKey,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String nodeId,
            @RequestParam(required = false) String issue,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        Viewer viewer = Viewer.from(authentication);
        int pageSize = DashboardQueryController.clampLimit(limit);
        Optional<SignedCursor.Key> after = cursors.decode(cursor);

        StringBuilder sql = new StringBuilder("""
                select t.id, t.project_key, t.state, t.priority, t.created_at, t.updated_at,
                       t.defect_id, t.base_sha, d.issue_iid, d.issue_url, d.title,
                       a.node_id as current_node_id
                from repair_task t
                join defect d on d.id = t.defect_id
                left join lateral (
                  select ra.node_id
                  from repair_attempt ra
                  where ra.task_id = t.id
                  order by ra.attempt_no desc
                  limit 1
                ) a on true
                where 1=1
                """);
        if (!viewer.admin()) {
            sql.append(" and t.project_key in (").append(Viewer.VISIBLE_PROJECTS_SQL).append(")");
        }
        if (projectKey != null && !projectKey.isBlank()) {
            sql.append(" and t.project_key = :projectKey");
        }
        if (state != null && !state.isBlank()) {
            sql.append(" and t.state = :state");
        }
        if (nodeId != null && !nodeId.isBlank()) {
            sql.append(" and cast(a.node_id as varchar) = :nodeId");
        }
        if (issue != null && !issue.isBlank()) {
            sql.append(" and (cast(d.issue_iid as varchar) = :issue or d.issue_url like :issueLike)");
        }
        if (from != null) {
            sql.append(" and t.created_at >= :fromTs");
        }
        if (to != null) {
            sql.append(" and t.created_at <= :toTs");
        }
        if (after.isPresent()) {
            sql.append("""
                     and (t.created_at < :cursorTs
                          or (t.created_at = :cursorTs and t.id < :cursorId))
                    """);
        }
        sql.append(" order by t.created_at desc, t.id desc limit :limit");

        var query = jdbc.sql(sql.toString()).param("limit", pageSize + 1);
        if (!viewer.admin()) {
            query = query.param("viewerId", viewer.gitlabUserId());
        }
        if (projectKey != null && !projectKey.isBlank()) {
            query = query.param("projectKey", projectKey);
        }
        if (state != null && !state.isBlank()) {
            query = query.param("state", state);
        }
        if (nodeId != null && !nodeId.isBlank()) {
            query = query.param("nodeId", nodeId);
        }
        if (issue != null && !issue.isBlank()) {
            query = query.param("issue", issue).param("issueLike", "%" + issue + "%");
        }
        if (from != null) {
            query = query.param("fromTs", Timestamp.from(from));
        }
        if (to != null) {
            query = query.param("toTs", Timestamp.from(to));
        }
        if (after.isPresent()) {
            query = query.param("cursorTs", Timestamp.from(after.get().timestamp()))
                    .param("cursorId", after.get().id());
        }

        List<Map<String, Object>> rows = query.query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class).toString());
                    row.put("projectKey", rs.getString("project_key"));
                    row.put("state", rs.getString("state"));
                    row.put("priority", rs.getInt("priority"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    row.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
                    row.put("defectId", rs.getObject("defect_id", UUID.class).toString());
                    row.put("baseSha", rs.getString("base_sha"));
                    row.put("issueIid", rs.getLong("issue_iid"));
                    row.put("issueUrl", rs.getString("issue_url"));
                    row.put("title", rs.getString("title"));
                    UUID node = rs.getObject("current_node_id", UUID.class);
                    row.put("nodeId", node == null ? null : node.toString());
                    return row;
                })
                .list();

        return DashboardQueryController.page(rows, pageSize, r -> {
            Instant ts = Instant.parse((String) r.get("createdAt"));
            UUID id = UUID.fromString((String) r.get("id"));
            return cursors.encode(new SignedCursor.Key(ts, id));
        });
    }

    @GetMapping("/{taskId}")
    Map<String, Object> detail(Authentication authentication, @PathVariable UUID taskId) {
        Viewer viewer = Viewer.from(authentication);
        if (!viewer.canSeeTask(jdbc, taskId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return jdbc.sql("""
                select t.id, t.project_key, t.state, t.priority, t.created_at, t.updated_at,
                       t.defect_id, t.defect_revision, t.profile_revision, t.base_sha,
                       t.profile_snapshot_json::text as profile_snapshot_json,
                       d.issue_iid, d.issue_url, d.title, d.description, d.state as defect_state,
                       d.missing_fields_json::text as missing_fields_json
                from repair_task t
                join defect d on d.id = t.defect_id
                where t.id = :id
                """)
                .param("id", taskId)
                .query((rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class).toString());
                    row.put("projectKey", rs.getString("project_key"));
                    row.put("state", rs.getString("state"));
                    row.put("priority", rs.getInt("priority"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    row.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
                    row.put("defectId", rs.getObject("defect_id", UUID.class).toString());
                    row.put("defectRevision", rs.getLong("defect_revision"));
                    row.put("profileRevision", rs.getLong("profile_revision"));
                    row.put("baseSha", rs.getString("base_sha"));
                    row.put("profileSnapshotJson", rs.getString("profile_snapshot_json"));
                    row.put("issueIid", rs.getLong("issue_iid"));
                    row.put("issueUrl", rs.getString("issue_url"));
                    row.put("title", rs.getString("title"));
                    row.put("description", rs.getString("description"));
                    row.put("defectState", rs.getString("defect_state"));
                    row.put("missingFieldsJson", rs.getString("missing_fields_json"));
                    return row;
                })
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
