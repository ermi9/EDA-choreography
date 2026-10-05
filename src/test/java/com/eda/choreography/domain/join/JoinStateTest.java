package com.eda.choreography.domain.join;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.Map;
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
    void keepsMessagesOnlyForBranchesThatArrived() {
        var message = ChoreographyMessage.start("corr-1", "flow", Map.of()).recordStep("B", Map.of());

        assertThatThrownBy(() -> new JoinState(JOIN, 2, Set.of("C"), false, Map.of("B", message)))
                .isInstanceOf(JoinProtocolException.class)
                .hasMessageContaining("B");
    }

    @Test
    void keepsMessagesOnlyOfItsOwnInstance() {
        var foreign = ChoreographyMessage.start("corr-2", "flow", Map.of()).recordStep("B", Map.of());

        assertThatThrownBy(() -> new JoinState(JOIN, 2, Set.of("B"), false, Map.of("B", foreign)))
                .isInstanceOf(JoinProtocolException.class)
                .hasMessageContaining("corr-2");
    }

    @Test
    void rejectsHavingFiredBeforeAllBranchesArrived() {
        assertThatThrownBy(() -> new JoinState(JOIN, 2, Set.of("B"), true))
                .isInstanceOf(JoinProtocolException.class);
    }
}
