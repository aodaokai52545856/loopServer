package com.company.loopengine.gitlab.webhook;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = GitLabWebhookController.class)
@TestPropertySource(properties = "loop.gitlab.webhook.token=hook-secret")
class GitLabWebhookControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean WebhookDeliveryRepository repository;

    @Test
    void acceptsAValidIssueHook() throws Exception {
        mvc.perform(post("/internal/webhooks/gitlab")
                .header("X-Gitlab-Token", "hook-secret")
                .header("X-Gitlab-Event", "Issue Hook")
                .header("X-Gitlab-Event-UUID", "evt-1")
                .contentType(APPLICATION_JSON).content(issuePayload()))
            .andExpect(status().isAccepted());
    }

    @Test
    void acceptsADuplicateIssueHook() throws Exception {
        mvc.perform(post("/internal/webhooks/gitlab")
                .header("X-Gitlab-Token", "hook-secret")
                .header("X-Gitlab-Event", "Issue Hook")
                .header("X-Gitlab-Event-UUID", "evt-dup")
                .contentType(APPLICATION_JSON).content(issuePayload()))
            .andExpect(status().isAccepted());
        mvc.perform(post("/internal/webhooks/gitlab")
                .header("X-Gitlab-Token", "hook-secret")
                .header("X-Gitlab-Event", "Issue Hook")
                .header("X-Gitlab-Event-UUID", "evt-dup")
                .contentType(APPLICATION_JSON).content(issuePayload()))
            .andExpect(status().isAccepted());
    }

    @Test
    void rejectsAnInvalidTokenWithoutPersisting() throws Exception {
        mvc.perform(post("/internal/webhooks/gitlab")
                .header("X-Gitlab-Token", "wrong")
                .header("X-Gitlab-Event", "Issue Hook")
                .header("X-Gitlab-Event-UUID", "evt-2")
                .contentType(APPLICATION_JSON).content(issuePayload()))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(repository);
    }

    private static String issuePayload() {
        return "{\"object_kind\":\"issue\"}";
    }
}
