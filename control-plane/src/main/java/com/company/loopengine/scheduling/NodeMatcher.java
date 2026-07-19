package com.company.loopengine.scheduling;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class NodeMatcher {
    public static final String NOT_ENABLED = "NOT_ENABLED";
    public static final String NOT_HEALTHY = "NOT_HEALTHY";
    public static final String PROJECT_NOT_MUTUALLY_ALLOWED = "PROJECT_NOT_MUTUALLY_ALLOWED";
    public static final String OS_ARCH_MISMATCH = "OS_ARCH_MISMATCH";
    public static final String TOOL_CONSTRAINT = "TOOL_CONSTRAINT";
    public static final String NO_FREE_SLOT = "NO_FREE_SLOT";
    public static final String INSUFFICIENT_MEMORY = "INSUFFICIENT_MEMORY";
    public static final String INSUFFICIENT_DISK = "INSUFFICIENT_DISK";

    private static final Set<String> HEALTHY_STATES = Set.of("ONLINE", "BUSY");

    private Map<UUID, String> lastRejectionReasons = Map.of();

    public List<NodeCandidate> rank(TaskRequirements task, List<NodeCandidate> candidates) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(candidates, "candidates");

        Map<UUID, String> rejections = new LinkedHashMap<>();
        List<NodeCandidate> eligible = new ArrayList<>();
        for (NodeCandidate candidate : candidates) {
            String reason = firstRejectionReason(task, candidate);
            if (reason != null) {
                rejections.put(candidate.id(), reason);
            } else {
                eligible.add(candidate);
            }
        }
        lastRejectionReasons = Map.copyOf(rejections);

        eligible.sort((left, right) -> score(task, left).compareTo(score(task, right)));
        return List.copyOf(eligible);
    }

    public Map<UUID, String> rejectionReasons() {
        return lastRejectionReasons;
    }

    public NodeScore score(TaskRequirements task, NodeCandidate candidate) {
        int requestedPenalty = task.requestedNodeId() != null
                && task.requestedNodeId().equals(candidate.id())
            ? 0
            : 1;
        int projectOwnerPenalty = candidate.projectOwner() ? 0 : 1;
        int cachePenalty = candidate.hasRepoCache() ? 0 : 1;
        int failureRate = failureRatePermille(candidate);
        int loadPermille = candidate.concurrencyLimit() <= 0
            ? 1000
            : candidate.activeSlots() * 1000 / candidate.concurrencyLimit();
        return new NodeScore(
            requestedPenalty,
            projectOwnerPenalty,
            cachePenalty,
            failureRate,
            loadPermille,
            candidate.lastAssignedAt(),
            candidate.id());
    }

    public static int failureRatePermille(List<Boolean> recentFinishedAttempts) {
        if (recentFinishedAttempts == null || recentFinishedAttempts.isEmpty()) {
            return 500;
        }
        int size = recentFinishedAttempts.size();
        int from = Math.max(0, size - 50);
        List<Boolean> window = recentFinishedAttempts.subList(from, size);
        long failures = window.stream().filter(success -> !success).count();
        return (int) (failures * 1000 / window.size());
    }

    private static int failureRatePermille(NodeCandidate candidate) {
        if (candidate.recentFinishedAttempts() != null) {
            return failureRatePermille(candidate.recentFinishedAttempts());
        }
        if (candidate.failureRatePermille() >= 0) {
            return candidate.failureRatePermille();
        }
        return 500;
    }

    private String firstRejectionReason(TaskRequirements task, NodeCandidate candidate) {
        // 1. enabled / health
        if (!candidate.enabled()) {
            return NOT_ENABLED;
        }
        if (!HEALTHY_STATES.contains(normalize(candidate.state()))) {
            return NOT_HEALTHY;
        }

        // 2. mutual project allowlist
        if (!mutuallyAllowed(task, candidate)) {
            return PROJECT_NOT_MUTUALLY_ALLOWED;
        }

        // 3. OS / arch
        if (!matchesOsArch(task, candidate)) {
            return OS_ARCH_MISMATCH;
        }

        // 4. semantic tool constraints
        if (candidate.javaMajor() < task.minJavaMajor()) {
            return TOOL_CONSTRAINT;
        }

        // 5. free slot
        if (candidate.activeSlots() >= candidate.concurrencyLimit()) {
            return NO_FREE_SLOT;
        }

        // 6. memory and disk
        if (candidate.memoryAvailableBytes() < task.minMemoryBytes()) {
            return INSUFFICIENT_MEMORY;
        }
        if (candidate.diskAvailableBytes() < task.minDiskBytes()) {
            return INSUFFICIENT_DISK;
        }
        return null;
    }

    private static boolean mutuallyAllowed(TaskRequirements task, NodeCandidate candidate) {
        if (candidate.allowedProjects() == null
                || !candidate.allowedProjects().contains(task.projectKey())) {
            return false;
        }
        Set<UUID> allowedNodeIds = task.allowedNodeIds();
        Set<String> allowedOwnerIds = task.allowedNodeOwnerIds();
        boolean projectUnrestricted =
            (allowedNodeIds == null || allowedNodeIds.isEmpty())
                && (allowedOwnerIds == null || allowedOwnerIds.isEmpty());
        if (projectUnrestricted) {
            return true;
        }
        if (allowedNodeIds != null && allowedNodeIds.contains(candidate.id())) {
            return true;
        }
        return allowedOwnerIds != null && allowedOwnerIds.contains(candidate.ownerId());
    }

    private static boolean matchesOsArch(TaskRequirements task, NodeCandidate candidate) {
        if (task.allowedOs() == null || task.allowedOs().isEmpty()) {
            return false;
        }
        if (!containsIgnoreCase(task.allowedOs(), candidate.os())) {
            return false;
        }
        if (task.allowedArch() == null || task.allowedArch().isEmpty()) {
            return true;
        }
        return containsIgnoreCase(task.allowedArch(), candidate.arch());
    }

    private static boolean containsIgnoreCase(Set<String> values, String needle) {
        if (needle == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String state) {
        return state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
    }

    public record TaskRequirements(
            String projectKey,
            Set<String> allowedOs,
            Set<String> allowedArch,
            int minJavaMajor,
            long minMemoryBytes,
            long minDiskBytes,
            UUID requestedNodeId,
            Set<UUID> allowedNodeIds,
            Set<String> allowedNodeOwnerIds) {}

    public record NodeCandidate(
            UUID id,
            String name,
            String state,
            boolean enabled,
            Set<String> allowedProjects,
            String ownerId,
            String os,
            String arch,
            int javaMajor,
            long memoryAvailableBytes,
            long diskAvailableBytes,
            int activeSlots,
            int concurrencyLimit,
            boolean projectOwner,
            boolean hasRepoCache,
            int failureRatePermille,
            Instant lastAssignedAt,
            List<Boolean> recentFinishedAttempts) {

        public NodeCandidate(
                UUID id,
                String name,
                String state,
                boolean enabled,
                Set<String> allowedProjects,
                String ownerId,
                String os,
                String arch,
                int javaMajor,
                long memoryAvailableBytes,
                long diskAvailableBytes,
                int activeSlots,
                int concurrencyLimit,
                boolean projectOwner,
                boolean hasRepoCache,
                int failureRatePermille,
                Instant lastAssignedAt) {
            this(
                id,
                name,
                state,
                enabled,
                allowedProjects,
                ownerId,
                os,
                arch,
                javaMajor,
                memoryAvailableBytes,
                diskAvailableBytes,
                activeSlots,
                concurrencyLimit,
                projectOwner,
                hasRepoCache,
                failureRatePermille,
                lastAssignedAt,
                null);
        }
    }
}
