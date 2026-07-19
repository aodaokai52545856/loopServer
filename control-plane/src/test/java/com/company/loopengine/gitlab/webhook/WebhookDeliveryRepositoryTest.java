package com.company.loopengine.gitlab.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class WebhookDeliveryRepositoryTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Autowired WebhookDeliveryRepository repository;

    @Test
    void storesTheSameGitLabEventUuidOnce() {
        String body = "{\"object_kind\":\"issue\"}";
        assertThat(repository.insert("evt-1", "Issue Hook", body, Instant.parse("2026-07-18T08:00:00Z"))).isTrue();
        assertThat(repository.insert("evt-1", "Issue Hook", body, Instant.parse("2026-07-18T08:00:01Z"))).isFalse();
        assertThat(repository.count()).isEqualTo(1);
    }
}
