package com.company.loopengine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryLayoutTest {
    @Test
    void ciDefinesAllLanguageChecks() throws Exception {
        String ci = Files.readString(Path.of("../.gitlab-ci.yml"));
        assertThat(ci).contains("java-test:", "web-test:", "go-test:", "contract-test:");
    }

    @Test
    void releasePipelineNamesEverySupportedArtifact() throws Exception {
        String ci = Files.readString(Path.of("../.gitlab/ci/node-release.yml"));
        assertThat(ci).contains(
            "repair-node-linux-amd64", "repair-node-linux-arm64",
            "repair-node-darwin-amd64", "repair-node-darwin-arm64",
            "repair-node-windows-amd64-beta");
    }
}
