package com.company.loopengine.project;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class ProjectProfileRepository {
    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public ProjectProfileRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public UUID create(String projectKey, String gitlabPath) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            insert into project_profile(id, project_key, gitlab_path)
            values (:id, :projectKey, :gitlabPath)
            """)
            .param("id", id)
            .param("projectKey", projectKey)
            .param("gitlabPath", gitlabPath)
            .update();
        return id;
    }

    @Transactional
    public long publish(UUID profileId, JsonNode config, String publishedBy) {
        jdbc.sql("select id from project_profile where id = :id for update")
            .param("id", profileId)
            .query(UUID.class)
            .single();

        long nextRevision = jdbc.sql("""
            select coalesce(max(revision), 0) + 1
            from project_profile_revision
            where profile_id = :id
            """)
            .param("id", profileId)
            .query(Long.class)
            .single();

        String canonicalJson = jsonMapper.writeValueAsString(config);
        String configSha256 = sha256Hex(canonicalJson);

        jdbc.sql("""
            insert into project_profile_revision(
              profile_id, revision, config_json, config_sha256, published_by)
            values (
              :profileId, :revision, cast(:config as jsonb), :sha, :publishedBy)
            """)
            .param("profileId", profileId)
            .param("revision", nextRevision)
            .param("config", canonicalJson)
            .param("sha", configSha256)
            .param("publishedBy", publishedBy)
            .update();

        jdbc.sql("""
            update project_profile
            set active_revision = :revision
            where id = :id
            """)
            .param("revision", nextRevision)
            .param("id", profileId)
            .update();

        return nextRevision;
    }

    public ProjectProfile getRevision(UUID profileId, long revision) {
        return jdbc.sql("""
            select config_json::text
            from project_profile_revision
            where profile_id = :profileId and revision = :revision
            """)
            .param("profileId", profileId)
            .param("revision", revision)
            .query((rs, rowNum) -> new ProjectProfile(jsonMapper.readTree(rs.getString(1))))
            .single();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
