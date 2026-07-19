package com.company.loopengine.scheduling;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

public record NodeScore(
        int requestedPenalty,
        int projectOwnerPenalty,
        int cachePenalty,
        int failureRatePermille,
        int loadPermille,
        Instant lastAssignedAt,
        UUID nodeId) implements Comparable<NodeScore> {

    private static final Comparator<NodeScore> ORDER = Comparator
        .comparingInt(NodeScore::requestedPenalty)
        .thenComparingInt(NodeScore::projectOwnerPenalty)
        .thenComparingInt(NodeScore::cachePenalty)
        .thenComparingInt(NodeScore::failureRatePermille)
        .thenComparingInt(NodeScore::loadPermille)
        .thenComparing(NodeScore::lastAssignedAt, Comparator.nullsFirst(Instant::compareTo))
        .thenComparing(NodeScore::nodeId);

    @Override
    public int compareTo(NodeScore other) {
        return ORDER.compare(this, other);
    }
}
