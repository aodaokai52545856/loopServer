package com.company.loopengine.security;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiAuthorizationTest.FixtureControllers.class)
@TestPropertySource(
        properties = {
            "loop.security.enabled=true",
            "spring.autoconfigure.exclude=",
            "spring.security.oauth2.client.registration.gitlab.client-id=test-client",
            "spring.security.oauth2.client.registration.gitlab.client-secret=test-secret",
            "spring.security.oauth2.client.registration.gitlab.authorization-grant-type=authorization_code",
            "spring.security.oauth2.client.registration.gitlab.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
            "spring.security.oauth2.client.registration.gitlab.scope=read_user,openid,profile,email",
            "spring.security.oauth2.client.registration.gitlab.provider=gitlab",
            "spring.security.oauth2.client.provider.gitlab.authorization-uri=http://gitlab.test/oauth/authorize",
            "spring.security.oauth2.client.provider.gitlab.token-uri=http://gitlab.test/oauth/token",
            "spring.security.oauth2.client.provider.gitlab.user-info-uri=http://gitlab.test/api/v4/user",
            "spring.security.oauth2.client.provider.gitlab.user-name-attribute=id",
            "loop.security.gitlab.admin-group=loop-admins",
            "loop.gitlab.url=http://gitlab.test"
        })
class ApiAuthorizationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @ParameterizedTest
    @CsvSource({
        "ADMIN,/api/admin/node-invites,POST,201",
        "PROJECT_OWNER,/api/projects/backend-a/revisions,POST,201",
        "OBSERVER,/api/projects/backend-a/revisions,POST,403",
        "NODE_OWNER,/api/nodes/node-owned/drain,POST,202",
        "NODE_OWNER,/api/nodes/node-other/drain,POST,403"
    })
    void enforcesRoleAndObjectOwnership(String role, String path, String method, int status)
            throws Exception {
        seedOwnership();
        performWithUser(role, path, method).andExpect(status().is(status));
    }

    @Test
    void unauthenticatedApiReturns401() throws Exception {
        mvc.perform(get("/api/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void validCsrfWriteSucceeds() throws Exception {
        seedOwnership();
        mvc.perform(post("/api/admin/node-invites")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());
    }

    private ResultActions performWithUser(String role, String path, String method) throws Exception {
        MockHttpServletRequestBuilder request =
                switch (method) {
                    case "POST" -> post(path).contentType(APPLICATION_JSON).content(bodyFor(path));
                    case "GET" -> get(path);
                    default -> throw new IllegalArgumentException(method);
                };
        String username =
                switch (role) {
                    case "PROJECT_OWNER" -> "1001";
                    case "NODE_OWNER" -> "user-NODE_OWNER";
                    case "ADMIN" -> "admin";
                    case "OBSERVER" -> "observer";
                    default -> "user-" + role;
                };
        return mvc.perform(request.with(user(username).roles(role)).with(csrf()));
    }

    private static String bodyFor(String path) {
        if (path.contains("/revisions")) {
            return """
                {
                  "repository": "group/backend-a",
                  "defaultBranch": "main",
                  "modules": ["services/order"],
                  "contextPaths": ["README.md"],
                  "validationCommands": [
                    {"program": "mvn", "args": ["-B", "test"], "timeoutSeconds": 1200}
                  ],
                  "allowedOs": ["linux"],
                  "allowedNodeIds": [],
                  "allowedNodeOwnerIds": ["backend-team"],
                  "requiredTools": {"java": ">=21"},
                  "forbiddenPaths": [".git/**"],
                  "maxChangedFiles": 40,
                  "maxPatchBytes": 1048576,
                  "maxRepairRounds": 2,
                  "maxExternalAttempts": 2,
                  "retryFunctionalFailure": false,
                  "targetBranch": "main",
                  "branchPrefix": "repair/",
                  "reviewers": ["backend-maintainers"]
                }
                """;
        }
        return "{}";
    }

    private void seedOwnership() {
        jdbc.sql(
                        """
            insert into project_profile (id, project_key, gitlab_path)
            values ('11111111-1111-1111-1111-111111111111', 'backend-a', 'group/backend-a')
            on conflict (project_key) do nothing
            """)
                .update();
        jdbc.sql(
                        """
            insert into project_profile_owner (profile_id, gitlab_user_id, added_by)
            values ('11111111-1111-1111-1111-111111111111', 1001, 'seed')
            on conflict do nothing
            """)
                .update();
        jdbc.sql(
                        """
            insert into repair_node (
              id, name, owner_id, public_key_sha256, certificate_serial, runner_tag, state,
              desired_config_json, concurrency_limit, allowed_projects_json, capabilities_json)
            values
              ('22222222-2222-2222-2222-222222222201', 'node-owned', 'user-NODE_OWNER',
               repeat('a', 64), 'serial-owned', 'tag-owned', 'ONLINE',
               '{"concurrency":1,"allowedProjects":[],"drain":false}'::jsonb, 1, '[]'::jsonb, '{}'::jsonb),
              ('22222222-2222-2222-2222-222222222202', 'node-other', 'someone-else',
               repeat('b', 64), 'serial-other', 'tag-other', 'ONLINE',
               '{"concurrency":1,"allowedProjects":[],"drain":false}'::jsonb, 1, '[]'::jsonb, '{}'::jsonb)
            on conflict (id) do nothing
            """)
                .update();
        // Paths in the role matrix use node name as the {nodeId} segment.
    }

    @TestConfiguration
    static class FixtureControllers {
        @Bean
        AdminInviteController adminInviteController() {
            return new AdminInviteController();
        }

        @Bean
        NodeDrainController nodeDrainController() {
            return new NodeDrainController();
        }
    }

    @RestController
    @RequestMapping("/api/admin")
    static class AdminInviteController {
        @PostMapping("/node-invites")
        ResponseEntity<Void> createInvite() {
            return ResponseEntity.status(HttpStatus.CREATED).build();
        }
    }

    @RestController
    @RequestMapping("/api/nodes")
    static class NodeDrainController {
        @PostMapping("/{nodeId}/drain")
        @PreAuthorize("@projectAuthorization.canDrainNode(authentication, #nodeId)")
        ResponseEntity<Void> drain(@PathVariable String nodeId) {
            return ResponseEntity.accepted().build();
        }
    }
}
