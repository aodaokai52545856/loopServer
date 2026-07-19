package com.company.loopengine.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ContractSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final Path contracts = Path.of(System.getProperty("basedir")).resolve("../contracts/v1").normalize();

    @Test
    void taskEventRequiresAttemptAndSequence() throws Exception {
        JsonSchema schema = schema("task-event.schema.json");
        JsonNode valid = mapper.readTree("""
          {"taskId":"t1","attemptId":"a1","nodeId":"n1","seq":1,
           "time":"2026-07-18T08:00:00Z","type":"agent.started","payload":{}}
          """);
        assertThat(schema.validate(valid)).isEmpty();
    }

    @Test
    void taskEventRejectsMissingAttemptId() throws Exception {
        JsonSchema schema = schema("task-event.schema.json");
        JsonNode invalid = mapper.readTree("""
          {"taskId":"t1","nodeId":"n1","seq":1,
           "time":"2026-07-18T08:00:00Z","type":"agent.started","payload":{}}
          """);
        assertThat(messages(schema.validate(invalid))).anyMatch(m -> m.contains("attemptId"));
    }

    @Test
    void taskPackageAcceptsValidDocument() throws Exception {
        JsonSchema schema = schema("task-package.schema.json");
        JsonNode valid = mapper.readTree("""
          {
            "protocol":"v1",
            "taskId":"11111111-1111-1111-1111-111111111111",
            "attemptId":"22222222-2222-2222-2222-222222222222",
            "nodeId":"33333333-3333-3333-3333-333333333333",
            "baseSha":"0123456789abcdef0123456789abcdef01234567",
            "project":{
              "key":"backend-a",
              "repositoryUrl":"https://gitlab.example/group/backend-a.git",
              "module":".",
              "targetBranch":"main",
              "profileRevision":1
            },
            "issue":{
              "projectId":1,
              "iid":42,
              "url":"https://gitlab.example/group/defect-intake/-/issues/42",
              "title":"NPE on login",
              "description":"stack trace",
              "attachments":[]
            },
            "validation":[
              {"program":"mvn","args":["-q","test"],"timeoutSeconds":600,"required":true}
            ],
            "callback":{
              "baseUrl":"https://control-plane.example",
              "eventsPath":"/api/node/v1/attempts/22222222-2222-2222-2222-222222222222/events:batch"
            }
          }
          """);
        assertThat(schema.validate(valid)).isEmpty();
    }

    @Test
    void taskPackageRejectsMissingProtocol() throws Exception {
        JsonSchema schema = schema("task-package.schema.json");
        JsonNode invalid = mapper.readTree("""
          {
            "taskId":"11111111-1111-1111-1111-111111111111",
            "attemptId":"22222222-2222-2222-2222-222222222222",
            "nodeId":"33333333-3333-3333-3333-333333333333",
            "baseSha":"0123456789abcdef0123456789abcdef01234567",
            "project":{
              "key":"backend-a",
              "repositoryUrl":"https://gitlab.example/group/backend-a.git",
              "module":".",
              "targetBranch":"main",
              "profileRevision":1
            },
            "issue":{
              "projectId":1,
              "iid":42,
              "url":"https://gitlab.example/group/defect-intake/-/issues/42",
              "title":"NPE on login",
              "description":"stack trace",
              "attachments":[]
            },
            "validation":[
              {"program":"mvn","args":["-q","test"],"timeoutSeconds":600,"required":true}
            ],
            "callback":{
              "baseUrl":"https://control-plane.example",
              "eventsPath":"/api/node/v1/attempts/22222222-2222-2222-2222-222222222222/events:batch"
            }
          }
          """);
        assertThat(messages(schema.validate(invalid))).anyMatch(m -> m.contains("protocol"));
    }

    @Test
    void resultManifestAcceptsValidDocument() throws Exception {
        JsonSchema schema = schema("result-manifest.schema.json");
        JsonNode valid = mapper.readTree("""
          {
            "protocol":"v1",
            "taskId":"11111111-1111-1111-1111-111111111111",
            "attemptId":"22222222-2222-2222-2222-222222222222",
            "nodeId":"33333333-3333-3333-3333-333333333333",
            "baseSha":"0123456789abcdef0123456789abcdef01234567",
            "patchSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "patchBytes":128,
            "changedFiles":[{"path":"src/Main.java","status":"modified"}],
            "validationResults":[
              {"program":"mvn","args":["-q","test"],"exitCode":0,"durationMs":1200}
            ],
            "eventLog":{
              "path":"events.jsonl",
              "sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "lastSeq":3
            },
            "startedAt":"2026-07-18T08:00:00Z",
            "finishedAt":"2026-07-18T08:05:00Z",
            "outcome":"SUCCEEDED"
          }
          """);
        assertThat(schema.validate(valid)).isEmpty();
    }

    @Test
    void resultManifestRejectsMissingOutcome() throws Exception {
        JsonSchema schema = schema("result-manifest.schema.json");
        JsonNode invalid = mapper.readTree("""
          {
            "protocol":"v1",
            "taskId":"11111111-1111-1111-1111-111111111111",
            "attemptId":"22222222-2222-2222-2222-222222222222",
            "nodeId":"33333333-3333-3333-3333-333333333333",
            "baseSha":"0123456789abcdef0123456789abcdef01234567",
            "patchSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "patchBytes":128,
            "changedFiles":[{"path":"src/Main.java","status":"modified"}],
            "validationResults":[
              {"program":"mvn","args":["-q","test"],"exitCode":0,"durationMs":1200}
            ],
            "eventLog":{
              "path":"events.jsonl",
              "sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "lastSeq":3
            },
            "startedAt":"2026-07-18T08:00:00Z",
            "finishedAt":"2026-07-18T08:05:00Z"
          }
          """);
        assertThat(messages(schema.validate(invalid))).anyMatch(m -> m.contains("outcome"));
    }

    private JsonSchema schema(String fileName) throws Exception {
        JsonNode schemaNode = mapper.readTree(contracts.resolve(fileName).toFile());
        return factory.getSchema(schemaNode);
    }

    private static Set<String> messages(Set<ValidationMessage> validationMessages) {
        return validationMessages.stream().map(ValidationMessage::getMessage).collect(Collectors.toSet());
    }
}
