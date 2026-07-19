package com.company.loopengine.defect.infra;

import com.company.loopengine.defect.application.ProcessIssueDelivery.Defect;
import com.company.loopengine.defect.application.ProcessIssueDelivery.IssueDelivery;
import com.company.loopengine.defect.domain.DefectState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DefectRepository {
    private final JdbcClient jdbc;

    public DefectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Defect> find(long intakeProjectId, long issueIid) {
        return jdbc.sql("""
            select id, intake_project_id, issue_iid, issue_global_id, issue_url, title, description,
                   state, missing_fields_json::text, source_updated_at, version
            from defect
            where intake_project_id = :projectId and issue_iid = :issueIid
            """)
            .param("projectId", intakeProjectId)
            .param("issueIid", issueIid)
            .query((rs, rowNum) -> new Defect(
                rs.getObject("id", UUID.class),
                rs.getLong("intake_project_id"),
                rs.getLong("issue_iid"),
                rs.getLong("issue_global_id"),
                rs.getString("issue_url"),
                rs.getString("title"),
                rs.getString("description"),
                DefectState.valueOf(rs.getString("state")),
                parseJsonArray(rs.getString("missing_fields_json")),
                rs.getTimestamp("source_updated_at").toInstant(),
                rs.getLong("version")))
            .optional();
    }

    public Defect save(Defect defect) {
        boolean exists = jdbc.sql("select count(*) from defect where id = :id")
            .param("id", defect.id())
            .query(Long.class)
            .single() > 0;
        if (!exists) {
            jdbc.sql("""
                insert into defect(
                  id, intake_project_id, issue_iid, issue_global_id, issue_url, title, description,
                  state, missing_fields_json, source_updated_at, version, created_at, updated_at)
                values (
                  :id, :projectId, :issueIid, :globalId, :url, :title, :description,
                  :state, cast(:missing as jsonb), :sourceUpdatedAt, :version, now(), now())
                """)
                .param("id", defect.id())
                .param("projectId", defect.intakeProjectId())
                .param("issueIid", defect.issueIid())
                .param("globalId", defect.issueGlobalId())
                .param("url", defect.issueUrl())
                .param("title", defect.title())
                .param("description", defect.description())
                .param("state", defect.state().name())
                .param("missing", toJsonArray(defect.missingFields()))
                .param("sourceUpdatedAt", Timestamp.from(defect.sourceUpdatedAt()))
                .param("version", defect.version())
                .update();
            return defect;
        }

        int updated = jdbc.sql("""
            update defect
            set issue_global_id = :globalId,
                issue_url = :url,
                title = :title,
                description = :description,
                state = :state,
                missing_fields_json = cast(:missing as jsonb),
                source_updated_at = :sourceUpdatedAt,
                version = version + 1,
                updated_at = now()
            where id = :id and version = :version
            """)
            .param("globalId", defect.issueGlobalId())
            .param("url", defect.issueUrl())
            .param("title", defect.title())
            .param("description", defect.description())
            .param("state", defect.state().name())
            .param("missing", toJsonArray(defect.missingFields()))
            .param("sourceUpdatedAt", Timestamp.from(defect.sourceUpdatedAt()))
            .param("id", defect.id())
            .param("version", defect.version())
            .update();
        if (updated == 0) {
            throw new OptimisticLockingFailureException(
                "defect version conflict for id=" + defect.id());
        }
        return defect.withVersion(defect.version() + 1);
    }

    public void saveSourceSnapshot(UUID id, IssueDelivery event) {
        int updated = jdbc.sql("""
            update defect
            set issue_global_id = :globalId,
                issue_url = :url,
                title = :title,
                description = :description,
                source_updated_at = :sourceUpdatedAt,
                version = version + 1,
                updated_at = now()
            where id = :id
            """)
            .param("globalId", event.issueGlobalId())
            .param("url", event.issueUrl())
            .param("title", event.title())
            .param("description", event.description())
            .param("sourceUpdatedAt", Timestamp.from(event.updatedAt()))
            .param("id", id)
            .update();
        if (updated == 0) {
            throw new OptimisticLockingFailureException("defect missing for snapshot id=" + id);
        }
    }

    public void appendTransition(
        UUID defectId,
        DefectState fromState,
        DefectState toState,
        String reason,
        String sourceEventUuid
    ) {
        jdbc.sql("""
            insert into defect_transition(defect_id, from_state, to_state, reason, source_event_uuid)
            values (:defectId, :fromState, :toState, :reason, :eventUuid)
            """)
            .param("defectId", defectId)
            .param("fromState", fromState == null ? null : fromState.name())
            .param("toState", toState.name())
            .param("reason", reason)
            .param("eventUuid", sourceEventUuid)
            .update();
    }

    public void replaceReferences(UUID defectId, List<String> imageUrls, Instant sourceUpdatedAt) {
        jdbc.sql("delete from defect_attachment where defect_id = :defectId")
            .param("defectId", defectId)
            .update();
        for (String url : imageUrls) {
            jdbc.sql("""
                insert into defect_attachment(
                  id, defect_id, source_url, name, source_updated_at)
                values (:id, :defectId, :url, :name, :sourceUpdatedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("defectId", defectId)
                .param("url", url)
                .param("name", nameFromUrl(url))
                .param("sourceUpdatedAt", Timestamp.from(sourceUpdatedAt))
                .update();
        }
    }

    public int countTransitions(long intakeProjectId, long issueIid) {
        return jdbc.sql("""
            select count(*) from defect_transition t
            join defect d on d.id = t.defect_id
            where d.intake_project_id = :projectId and d.issue_iid = :issueIid
            """)
            .param("projectId", intakeProjectId)
            .param("issueIid", issueIid)
            .query(Integer.class)
            .single();
    }

    private static String nameFromUrl(String url) {
        int slash = url.lastIndexOf('/');
        return slash >= 0 && slash < url.length() - 1 ? url.substring(slash + 1) : url;
    }

    private static List<String> parseJsonArray(String json) {
        String trimmed = json == null ? "[]" : json.trim();
        if (trimmed.equals("[]") || trimmed.isEmpty()) {
            return List.of();
        }
        String body = trimmed.substring(1, trimmed.length() - 1);
        return Arrays.stream(body.split(","))
            .map(String::trim)
            .map(s -> s.replaceAll("^\"|\"$", ""))
            .filter(s -> !s.isEmpty())
            .toList();
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i)).append('"');
        }
        return sb.append(']').toString();
    }
}
