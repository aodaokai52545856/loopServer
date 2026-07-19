package com.company.loopengine.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.loopengine.scheduling.NodeMatcher.NodeCandidate;
import com.company.loopengine.scheduling.NodeMatcher.TaskRequirements;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NodeMatcherTest {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final String ONLINE = "ONLINE";
    private static final String OFFLINE = "OFFLINE";

    private NodeMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new NodeMatcher();
    }

    @Test
    void filtersIneligibleNodesAndPrefersTheRequestedNode() {
        TaskRequirements task = javaTask("backend-a", Set.of("linux", "darwin"), 21, 8L * GIB);
        List<NodeCandidate> result = matcher.rank(task, List.of(
            node("offline", OFFLINE, "linux", 21, 64, 0, 2, false, false, 99),
            node("small", ONLINE, "linux", 21, 4, 0, 2, false, false, 99),
            node("owner", ONLINE, "darwin", 21, 32, 1, 4, true, true, 70),
            node("idle", ONLINE, "linux", 21, 32, 0, 4, false, true, 90)));
        assertThat(result).extracting(NodeCandidate::name).containsExactly("owner", "idle");
    }

    @Test
    void recordsFirstRejectionReasonForEveryCandidate() {
        TaskRequirements task = javaTask("backend-a", Set.of("linux", "darwin"), 21, 8L * GIB);
        List<NodeCandidate> candidates = List.of(
            node("offline", OFFLINE, "linux", 21, 64, 0, 2, false, false, 99),
            node("small", ONLINE, "linux", 21, 4, 0, 2, false, false, 99),
            node("owner", ONLINE, "darwin", 21, 32, 1, 4, true, true, 70),
            node("idle", ONLINE, "linux", 21, 32, 0, 4, false, true, 90));

        matcher.rank(task, candidates);
        Map<UUID, String> rejections = matcher.rejectionReasons();

        assertThat(rejections.get(idFor("offline"))).isEqualTo("NOT_HEALTHY");
        assertThat(rejections.get(idFor("small"))).isEqualTo("INSUFFICIENT_MEMORY");
        assertThat(rejections).doesNotContainKeys(idFor("owner"), idFor("idle"));
    }

    @Test
    void rankingIsStableAcrossInputShuffles() {
        TaskRequirements task = javaTask("backend-a", Set.of("linux", "darwin"), 21, 8L * GIB);
        List<NodeCandidate> original = List.of(
            node("offline", OFFLINE, "linux", 21, 64, 0, 2, false, false, 99),
            node("small", ONLINE, "linux", 21, 4, 0, 2, false, false, 99),
            node("owner", ONLINE, "darwin", 21, 32, 1, 4, true, true, 70),
            node("idle", ONLINE, "linux", 21, 32, 0, 4, false, true, 90),
            node("busy", ONLINE, "linux", 21, 32, 3, 4, false, false, 40));

        List<String> expected = matcher.rank(task, original).stream().map(NodeCandidate::name).toList();

        List<NodeCandidate> shuffled = new ArrayList<>(original);
        for (int i = 0; i < 100; i++) {
            Collections.shuffle(shuffled);
            List<String> ranked = matcher.rank(task, shuffled).stream().map(NodeCandidate::name).toList();
            assertThat(ranked).as("shuffle iteration %s", i).containsExactlyElementsOf(expected);
        }
    }

    private TaskRequirements javaTask(
            String projectKey, Set<String> allowedOs, int minJavaMajor, long minMemoryBytes) {
        return new TaskRequirements(
            projectKey,
            allowedOs,
            Set.of("amd64"),
            minJavaMajor,
            minMemoryBytes,
            10L * GIB,
            idFor("owner"),
            Set.of(),
            Set.of());
    }

    private NodeCandidate node(
            String name,
            String state,
            String os,
            int javaMajor,
            int memoryGiB,
            int activeSlots,
            int concurrencyLimit,
            boolean projectOwner,
            boolean hasCache,
            int failureRatePermille) {
        return new NodeCandidate(
            idFor(name),
            name,
            state,
            true,
            Set.of("backend-a"),
            "owner-" + name,
            os,
            "amd64",
            javaMajor,
            memoryGiB * GIB,
            50L * GIB,
            activeSlots,
            concurrencyLimit,
            projectOwner,
            hasCache,
            failureRatePermille,
            Instant.parse("2026-01-01T00:00:00Z").plusSeconds(name.hashCode() & 0xffff));
    }

    private static UUID idFor(String name) {
        return UUID.nameUUIDFromBytes(("node:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
