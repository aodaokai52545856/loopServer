package com.company.loopengine.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@ConditionalOnProperty(name = "loop.security.enabled", havingValue = "true")
public class GitLabUserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private static final Logger log = LoggerFactory.getLogger(GitLabUserService.class);

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final JdbcClient jdbc;
    private final String adminGroup;
    private final String gitlabUrl;

    public GitLabUserService(
            JdbcClient jdbc,
            @Value("${loop.security.gitlab.admin-group:}") String adminGroup,
            @Value("${loop.gitlab.url:}") String gitlabUrl) {
        this.jdbc = jdbc;
        this.adminGroup = adminGroup == null ? "" : adminGroup.trim();
        this.gitlabUrl = gitlabUrl == null ? "" : gitlabUrl.replaceAll("/$", "");
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User user = delegate.loadUser(userRequest);
        // Never trust a role claim supplied by the browser; derive roles server-side only.
        Set<GrantedAuthority> authorities = new HashSet<>();
        long gitlabUserId = extractGitlabUserId(user);
        if (isAdministrator(userRequest, user)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        if (ownsAnyProject(gitlabUserId)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_PROJECT_OWNER"));
        }
        if (ownsAnyNode(String.valueOf(gitlabUserId)) || ownsAnyNode(user.getName())) {
            authorities.add(new SimpleGrantedAuthority("ROLE_NODE_OWNER"));
        }
        if (authorities.isEmpty()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_OBSERVER"));
        }
        Map<String, Object> attributes = Map.copyOf(user.getAttributes());
        log.debug("Mapped GitLab user {} to authorities {}", gitlabUserId, authorityNames(authorities));
        return new DefaultOAuth2User(authorities, attributes, "id");
    }

    private boolean isAdministrator(OAuth2UserRequest userRequest, OAuth2User user) {
        if (adminGroup.isBlank() || gitlabUrl.isBlank()) {
            return false;
        }
        Object groupsAttr = user.getAttribute("groups");
        if (groupsAttr instanceof Collection<?> groups) {
            for (Object group : groups) {
                if (adminGroup.equals(String.valueOf(group))) {
                    return true;
                }
            }
        }
        try {
            String token = userRequest.getAccessToken().getTokenValue();
            RestClient.create()
                .get()
                .uri(gitlabUrl + "/api/v4/groups/{group}/members/{userId}", adminGroup, extractGitlabUserId(user))
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
            return true;
        } catch (RuntimeException ex) {
            log.debug("Admin group check failed for group {}: {}", adminGroup, ex.toString());
            return false;
        }
    }

    private boolean ownsAnyProject(long gitlabUserId) {
        Boolean owned = jdbc.sql("""
                select exists(
                  select 1 from project_profile_owner where gitlab_user_id = :gitlabUserId
                )
                """)
            .param("gitlabUserId", gitlabUserId)
            .query(Boolean.class)
            .single();
        return Boolean.TRUE.equals(owned);
    }

    private boolean ownsAnyNode(String ownerId) {
        Boolean owned = jdbc.sql("""
                select exists(
                  select 1 from repair_node where owner_id = :ownerId
                )
                """)
            .param("ownerId", ownerId)
            .query(Boolean.class)
            .single();
        return Boolean.TRUE.equals(owned);
    }

    private static long extractGitlabUserId(OAuth2User user) {
        Object id = user.getAttribute("id");
        if (id instanceof Number number) {
            return number.longValue();
        }
        if (id != null) {
            return Long.parseLong(String.valueOf(id));
        }
        return Long.parseLong(user.getName());
    }

    private static List<String> authorityNames(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).sorted().toList();
    }
}
