package com.company.loopengine.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class OutboxRepositoryTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    @Autowired OutboxRepository repository;

    @Test
    void appendsAnEventOnce() {
        UUID id = UUID.randomUUID();
        repository.append(new OutboxEvent(id, "DEFECT", "d-1", "ISSUE_LABEL_SET", "{}", Instant.now()));
        assertThat(repository.findPending(10)).extracting(OutboxEvent::id).containsExactly(id);
    }
}
