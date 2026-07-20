package com.company.loopengine.execution.job;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.company.loopengine.execution.job.RepairJobService.JobHook;
import java.security.MessageDigest;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/internal/webhooks/gitlab/jobs")
class RepairJobWebhookController {
    private final String token;
    private final long centralProjectId;
    private final RepairJobService service;
    private final JsonMapper jsonMapper;

    @Autowired
    RepairJobWebhookController(
            @Value("${loop.gitlab.job-webhook.token:}") String token,
            @Value("${loop.gitlab.central-project-id:0}") long centralProjectId,
            RepairJobService service,
            JsonMapper jsonMapper) {
        this.token = Objects.requireNonNullElse(token, "");
        this.centralProjectId = centralProjectId;
        this.service = Objects.requireNonNull(service, "service");
        this.jsonMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
    }

    /** Package-visible constructor for focused unit tests without Spring JSON wiring. */
    RepairJobWebhookController(String token, long centralProjectId, RepairJobService service) {
        this.token = Objects.requireNonNullElse(token, "");
        this.centralProjectId = centralProjectId;
        this.service = Objects.requireNonNull(service, "service");
        this.jsonMapper = JsonMapper.builder().build();
    }

    @PostMapping
    ResponseEntity<Void> receive(
            @RequestHeader("X-Gitlab-Token") String requestToken,
            @RequestHeader("X-Gitlab-Event") String event,
            @RequestHeader("X-Gitlab-Event-UUID") String eventUuid,
            @RequestBody String body) {
        if (!MessageDigest.isEqual(requestToken.getBytes(UTF_8), token.getBytes(UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!"Job Hook".equals(event) && !"Build Hook".equals(event)) {
            return ResponseEntity.accepted().build();
        }
        JsonNode root = jsonMapper.readTree(body);
        long projectId = root.path("project_id").asLong();
        if (projectId != centralProjectId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        JobHook hook = service.parseHook(eventUuid, body);
        service.handle(hook);
        return ResponseEntity.accepted().build();
    }
}
