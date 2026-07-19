package com.company.loopengine.defect.domain;

import static com.company.loopengine.defect.domain.DefectState.NEEDS_INFO;
import static com.company.loopengine.defect.domain.DefectState.NEW;
import static com.company.loopengine.defect.domain.DefectState.QUEUED;
import static com.company.loopengine.defect.domain.DefectState.READY_FOR_TEST;
import static com.company.loopengine.defect.domain.DefectState.RUNNING;
import static com.company.loopengine.defect.domain.DefectState.TRIAGING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefectStateMachineTest {
    private DefectStateMachine machine;

    @BeforeEach
    void setUp() {
        machine = new DefectStateMachine();
    }

    @Test
    void allowsOnlyDeclaredTransitions() {
        assertThat(machine.canMove(NEW, TRIAGING)).isTrue();
        assertThat(machine.canMove(TRIAGING, NEEDS_INFO)).isTrue();
        assertThat(machine.canMove(TRIAGING, QUEUED)).isTrue();
        assertThat(machine.canMove(NEEDS_INFO, TRIAGING)).isTrue();
        assertThat(machine.canMove(READY_FOR_TEST, RUNNING)).isFalse();
        assertThatThrownBy(() -> machine.requireMove(READY_FOR_TEST, RUNNING))
            .isInstanceOf(IllegalStateException.class);
    }
}
