package com.company.loopengine.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Testcontainers
@SpringBootTest
class ProjectProfileRepositoryTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    ProjectProfileRepository repository;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void publishingCreatesAnImmutableRevision() {
        UUID profileId = repository.create("backend-a", "group/backend-a");
        long first = repository.publish(profileId, profileJson("mvn -B test"), "alice");
        long second = repository.publish(profileId, profileJson("mvn -B verify"), "alice");

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        assertThat(repository.getRevision(profileId, 1).config().path("validationCommands").get(0).asText())
            .isEqualTo("mvn -B test");
    }

    private JsonNode profileJson(String validationCommand) {
        ObjectNode root = jsonMapper.createObjectNode();
        ArrayNode commands = root.putArray("validationCommands");
        commands.add(validationCommand);
        return root;
    }
}
