package com.eda.choreography.domain.join;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.join.JoinOutcome.Decision;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The INC-2 exit tests: a join fires exactly once when all branches have arrived, whatever the
 * order, and duplicates never make it fire early or fire again.
 */
class JoinStateMachineTest {

    private static final JoinKey JOIN = new JoinKey("corr-1", "J");

    private final JoinStateMachine machine = new JoinStateMachine(new InMemoryJoinStateStore());

    @Test
    void happyPathFiresOnceWhenTheLastBranchArrives() {
        assertThat(arrive("B", 3).decision()).isEqualTo(Decision.WAITING);
        assertThat(arrive("C", 3).decision()).isEqualTo(Decision.WAITING);

        var last = arrive("D", 3);

        assertThat(last.decision()).isEqualTo(Decision.FIRED);
        assertThat(last.state().arrivedBranches()).containsExactlyInAnyOrder("B", "C", "D");
        assertThat(last.state().fired()).isTrue();
    }

    @Test
    void everyArrivalOrderFiresExactlyOnceOnTheLastBranch() {
        for (var order : permutations(List.of("B", "C", "D"))) {
            var fresh = new JoinStateMachine(new InMemoryJoinStateStore());

            var decisions = order.stream().map(b -> fresh.arrive(arrival(b, 3)).decision()).toList();

            assertThat(decisions)
                    .as("arrival order %s", order)
                    .containsExactly(Decision.WAITING, Decision.WAITING, Decision.FIRED);
        }
    }

    @Test
    void duplicateDoesNotMakeTheJoinFireEarly() {
        arrive("B", 3);
        var repeat = arrive("B", 3);
        var second = arrive("C", 3);

        assertThat(repeat.decision()).isEqualTo(Decision.DUPLICATE);
        assertThat(second.decision()).isEqualTo(Decision.WAITING);
        assertThat(second.state().arrivedBranches()).containsExactlyInAnyOrder("B", "C");
        assertThat(arrive("D", 3).fired()).isTrue();
    }

    @Test
    void incompleteJoinNeverFires() {
        var outcomes = List.of(arrive("B", 3), arrive("C", 3), arrive("C", 3), arrive("B", 3));

        assertThat(outcomes).noneMatch(JoinOutcome::fired);
        assertThat(outcomes.get(outcomes.size() - 1).state().fired()).isFalse();
    }

    @Test
    void duplicateAfterFiringDoesNotFireAgain() {
        arrive("B", 2);
        assertThat(arrive("C", 2).fired()).isTrue();

        var lateB = arrive("B", 2);
        var lateC = arrive("C", 2);

        assertThat(lateB.decision()).isEqualTo(Decision.DUPLICATE);
        assertThat(lateC.decision()).isEqualTo(Decision.DUPLICATE);
        assertThat(lateC.state().fired()).isTrue();
    }

    @Test
    void singleBranchJoinFiresOnItsOnlyArrival() {
        assertThat(arrive("B", 1).fired()).isTrue();
        assertThat(arrive("B", 1).decision()).isEqualTo(Decision.DUPLICATE);
    }

    @Test
    void joinsOfDifferentInstancesDoNotShareBranches() {
        var other = new JoinKey("corr-2", "J");

        machine.arrive(new BranchArrival(JOIN, "B", 2));
        var otherInstance = machine.arrive(new BranchArrival(other, "C", 2));

        assertThat(otherInstance.decision()).isEqualTo(Decision.WAITING);
        assertThat(otherInstance.state().arrivedBranches()).containsExactly("C");
    }

    @Test
    void twoJoinsOfOneInstanceDoNotShareBranches() {
        var secondJoin = new JoinKey("corr-1", "K");

        machine.arrive(new BranchArrival(JOIN, "B", 2));
        var atSecondJoin = machine.arrive(new BranchArrival(secondJoin, "C", 2));

        assertThat(atSecondJoin.decision()).isEqualTo(Decision.WAITING);
        assertThat(atSecondJoin.state().arrivedBranches()).containsExactly("C");
    }

    @Test
    void rejectsAnExtraDistinctBranchAfterTheJoinIsFull() {
        arrive("B", 2);
        arrive("C", 2);

        assertThatThrownBy(() -> arrive("D", 2))
                .isInstanceOf(JoinProtocolException.class)
                .hasMessageContaining("expected 2");
    }

    @Test
    void rejectsBranchesThatDisagreeOnHowManyAreExpected() {
        arrive("B", 3);

        assertThatThrownBy(() -> arrive("C", 2))
                .isInstanceOf(JoinProtocolException.class)
                .hasMessageContaining("expectedBranches");
    }

    @Test
    void rejectedArrivalLeavesTheJoinUnchanged() {
        arrive("B", 3);
        assertThatThrownBy(() -> arrive("C", 2)).isInstanceOf(JoinProtocolException.class);

        assertThat(arrive("C", 3).decision()).isEqualTo(Decision.WAITING);
        assertThat(arrive("D", 3).fired()).isTrue();
    }

    private JoinOutcome arrive(String branchId, int expected) {
        return machine.arrive(arrival(branchId, expected));
    }

    private static BranchArrival arrival(String branchId, int expected) {
        return new BranchArrival(JOIN, branchId, expected);
    }

    private static List<List<String>> permutations(List<String> items) {
        if (items.isEmpty()) {
            return List.of(List.of());
        }
        var result = new ArrayList<List<String>>();
        for (var head : items) {
            var rest = new ArrayList<>(items);
            rest.remove(head);
            for (var tail : permutations(rest)) {
                var perm = new ArrayList<String>();
                perm.add(head);
                perm.addAll(tail);
                result.add(perm);
            }
        }
        return result;
    }
}
