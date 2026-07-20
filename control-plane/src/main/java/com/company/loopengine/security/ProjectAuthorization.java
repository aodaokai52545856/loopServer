package com.company.loopengine.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

@Component("projectAuthorization")
public class ProjectAuthorization {
    private final JdbcClient jdbc;

    public ProjectAuthorization(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean canEditProject(Authentication authentication, String projectKey) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (hasRole(authentication, "ADMIN")) {
            return true;
        }
        if (!hasRole(authentication, "PROJECT_OWNER")) {
            return false;
        }
        long gitlabUserId;
        try {
            gitlabUserId = Long.parseLong(authentication.getName());
        } catch (NumberFormatException ex) {
            return false;
        }
        Boolean owned = jdbc.sql("""
                select exists(
                  select 1
                  from project_profile_owner o
                  join project_profile p on p.id = o.profile_id
                  where p.project_key = :projectKey
                    and o.gitlab_user_id = :gitlabUserId
                )
                """)
            .param("projectKey", projectKey)
            .param("gitlabUserId", gitlabUserId)
            .query(Boolean.class)
            .single();
        return Boolean.TRUE.equals(owned);
    }

    public boolean canDrainNode(Authentication authentication, String nodeId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (hasRole(authentication, "ADMIN")) {
            return true;
        }
        if (!hasRole(authentication, "NODE_OWNER")) {
            return false;
        }
        Boolean owned = jdbc.sql("""
                select exists(
                  select 1
                  from repair_node
                  where owner_id = :ownerId
                    and (name = :nodeId or cast(id as varchar) = :nodeId)
                )
                """)
            .param("ownerId", authentication.getName())
            .param("nodeId", nodeId)
            .query(Boolean.class)
            .single();
        return Boolean.TRUE.equals(owned);
    }

    private static boolean hasRole(Authentication authentication, String role) {
        String needle = "ROLE_" + role;
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (needle.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
