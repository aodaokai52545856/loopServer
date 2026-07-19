package com.company.loopengine.gitlab.webhook;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/webhooks/gitlab")
@EnableConfigurationProperties(GitLabWebhookProperties.class)
class GitLabWebhookController {
    private final GitLabWebhookProperties properties;
    private final WebhookDeliveryRepository repository;

    GitLabWebhookController(GitLabWebhookProperties properties, WebhookDeliveryRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    @PostMapping
    ResponseEntity<Void> receive(
            @RequestHeader("X-Gitlab-Token") String token,
            @RequestHeader("X-Gitlab-Event") String event,
            @RequestHeader("X-Gitlab-Event-UUID") String uuid,
            @RequestBody String body) {
        if (!MessageDigest.isEqual(token.getBytes(UTF_8), properties.token().getBytes(UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!event.equals("Issue Hook")) {
            return ResponseEntity.accepted().build();
        }
        repository.insert(uuid, event, body, Instant.now());
        return ResponseEntity.accepted().build();
    }
}
