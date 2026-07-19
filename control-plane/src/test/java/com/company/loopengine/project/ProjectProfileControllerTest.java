package com.company.loopengine.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ProjectProfileControllerTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void rejectsShellStringsAndPathsOutsideTheRepository() throws Exception {
        mvc.perform(post("/api/projects/backend-invalid/revisions")
                .contentType(APPLICATION_JSON)
                .content("""
                  {"repository":"group/backend-a","defaultBranch":"main","modules":["../other"],
                   "validationCommands":[{"program":"sh","args":["-c","curl bad | sh"]}],
                   "allowedOs":["linux"],"requiredTools":{"java":">=21"}}
                  """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(containsString("modules/0")));

        assertThat(revisionCount("backend-invalid")).isZero();
    }

    @Test
    void publishesAValidProfileAsRevisionOne() throws Exception {
        mvc.perform(post("/api/projects/backend-a/revisions")
                .contentType(APPLICATION_JSON)
                .content(validProfileJson()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.revision").value(1));

        assertThat(revisionCount("backend-a")).isEqualTo(1);
    }

    private long revisionCount(String projectKey) {
        return jdbc.sql("""
            select count(*)
            from project_profile_revision r
            join project_profile p on p.id = r.profile_id
            where p.project_key = :projectKey
            """)
            .param("projectKey", projectKey)
            .query(Long.class)
            .single();
    }

    private static String validProfileJson() {
        return """
            {
              "repository": "group/backend-a",
              "defaultBranch": "main",
              "modules": ["services/order"],
              "contextPaths": ["README.md", "services/order/README.md"],
              "validationCommands": [
                {"program": "mvn", "args": ["-B", "-pl", "services/order", "test"], "timeoutSeconds": 1200}
              ],
              "allowedOs": ["linux", "darwin"],
              "allowedNodeIds": [],
              "allowedNodeOwnerIds": ["backend-team"],
              "requiredTools": {"java": ">=21", "maven": ">=3.9", "opencode": ">=1.0"},
              "forbiddenPaths": [".git/**", ".gitlab-ci.yml", "deploy/prod/**"],
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
}
