package com.eda.choreography.domain.join;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class JoinStateTest {

    private static final JoinKey JOIN = new JoinKey("corr-1", "J");

    @Test
    void completesWhenEveryExpectedBranchHasArrived() {
        var one = JoinState.open(JOIN, 2).withArrival("B");
        var both = one.withArrival("C");

        assertThat(one.isComplete()).isFalse();
        assertThat(both.isComplete()).isTrue();
        assertThat(both.arrivedBranches()).containsExactlyInAnyOrder("B", "C");
    }

    @Test
    void theSameBranchArrivingTwiceCountsOnce() {
        var state = JoinState.open(JOIN, 2).withArrival("B").withArrival("B");

        assertThat(state.arrivedBranches()).containsExactly("B");
        assertThat(state.isComplete()).isFalse();
    }

    @Test
    void rejectsMoreBranchesThanExpected() {
        assertThatThrownBy(() -> new JoinState(JOIN, 1, Set.of("B", "C"), false))
                .isInstanceOf(JoinProtocolException.class)
                .hasMessageContaining("expected 1");
    }

    @Test
    void rejectsHavingFiredBeforeAllBranchesArrived() {
        assertThatThrownBy(() -> new JoinState(JOIN, 2, Set.of("B"), true))
                .isInstanceOf(JoinProtocolException.class);
    }
}
