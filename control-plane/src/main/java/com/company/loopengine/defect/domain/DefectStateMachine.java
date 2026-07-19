package com.company.loopengine.defect.domain;

import static com.company.loopengine.defect.domain.DefectState.BLOCKED;
import static com.company.loopengine.defect.domain.DefectState.CANCELLED;
import static com.company.loopengine.defect.domain.DefectState.FAILED;
import static com.company.loopengine.defect.domain.DefectState.NEEDS_INFO;
import static com.company.loopengine.defect.domain.DefectState.NEW;
import static com.company.loopengine.defect.domain.DefectState.QUEUED;
import static com.company.loopengine.defect.domain.DefectState.READY_FOR_TEST;
import static com.company.loopengine.defect.domain.DefectState.RUNNING;
import static com.company.loopengine.defect.domain.DefectState.TRIAGING;

import java.util.Map;
import java.util.Set;

public final class DefectStateMachine {
    private static final Map<DefectState, Set<DefectState>> ALLOWED = Map.of(
        NEW, Set.of(TRIAGING, CANCELLED),
        TRIAGING, Set.of(NEEDS_INFO, QUEUED, CANCELLED),
        NEEDS_INFO, Set.of(TRIAGING, CANCELLED),
        QUEUED, Set.of(TRIAGING, RUNNING, BLOCKED, CANCELLED),
        RUNNING, Set.of(BLOCKED, FAILED, READY_FOR_TEST, CANCELLED),
        BLOCKED, Set.of(TRIAGING, QUEUED, CANCELLED),
        FAILED, Set.of(TRIAGING, QUEUED, CANCELLED),
        READY_FOR_TEST, Set.of(CANCELLED),
        CANCELLED, Set.of()
    );

    public boolean canMove(DefectState from, DefectState to) {
        return ALLOWED.get(from).contains(to);
    }

    public void requireMove(DefectState from, DefectState to) {
        if (!canMove(from, to)) {
            throw new IllegalStateException("Illegal defect transition: " + from + " -> " + to);
        }
    }
}
