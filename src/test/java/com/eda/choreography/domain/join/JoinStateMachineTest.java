package com.eda.choreography.domain.join;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.join.JoinOutcome.Decision;
import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    @Test
    void firingHandsBackTheMessageOfEveryBranch() {
        var forked = ChoreographyMessage.start("corr-1", "flow", Map.of()).recordStep("A", Map.of());
        var left = forked.recordStep("B", Map.of("side", "left"));
        var right = forked.recordStep("C", Map.of("side", "right"));

        machine.arrive(new BranchArrival(JOIN, "B", 2, left));
        var fired = machine.arrive(new BranchArrival(JOIN, "C", 2, right));

        assertThat(fired.state().branchMessages()).containsOnly(Map.entry("B", left), Map.entry("C", right));
    }

    @Test
    void aDuplicateDoesNotReplaceTheMessageAlreadyKept() {
        var forked = ChoreographyMessage.start("corr-1", "flow", Map.of()).recordStep("A", Map.of());
        var first = forked.recordStep("B", Map.of("run", 1));

        machine.arrive(new BranchArrival(JOIN, "B", 2, first));
        var repeat = machine.arrive(new BranchArrival(JOIN, "B", 2, forked.recordStep("B", Map.of("run", 2))));

        assertThat(repeat.state().branchMessages()).containsOnly(Map.entry("B", first));
    }

    @Test
    void anOpenJoinTimesOutWithTheBranchesThatDidArrive() {
        var left = ChoreographyMessage.start("corr-1", "flow", Map.of()).recordStep("B", Map.of());
        machine.arrive(new BranchArrival(JOIN, "B", 2, left));

        var timedOut = machine.timeOut(JOIN);

        assertThat(timedOut).hasValueSatisfying(state -> {
            assertThat(state.status()).isEqualTo(JoinState.Status.TIMED_OUT);
            assertThat(state.branchMessages()).containsOnly(Map.entry("B", left));
        });
    }

    @Test
    void timingOutTwiceHandsBackTheSameStateSoTheCallerCanFinishWhatItStarted() {
        arrive("B", 2);

        var first = machine.timeOut(JOIN);

        assertThat(first).isPresent();
        assertThat(machine.timeOut(JOIN)).isEqualTo(first);
    }

    @Test
    void aJoinThatFiredOrNeverOpenedDoesNotTimeOut() {
        arrive("B", 1);

        assertThat(machine.timeOut(JOIN)).isEmpty();
        assertThat(machine.timeOut(new JoinKey("corr-1", "never-reached"))).isEmpty();
        assertThat(arrive("B", 1).state().fired()).isTrue();
    }

    @Test
    void aNewBranchAfterTheTimeoutIsLateAndDoesNotReopenTheJoin() {
        arrive("B", 2);
        machine.timeOut(JOIN);

        var late = arrive("C", 2);

        assertThat(late.decision()).isEqualTo(Decision.LATE);
        assertThat(late.state().arrivedBranches()).containsExactly("B");
        assertThat(late.state().status()).isEqualTo(JoinState.Status.TIMED_OUT);
    }

    @Test
    void aBranchThatArrivedBeforeTheTimeoutIsStillADuplicate() {
        arrive("B", 2);
        machine.timeOut(JOIN);

        assertThat(arrive("B", 2).decision()).isEqualTo(Decision.DUPLICATE);
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
