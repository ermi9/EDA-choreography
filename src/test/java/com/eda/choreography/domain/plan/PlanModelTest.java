package com.eda.choreography.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.plan.PlanNode.Split;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PlanModelTest {

    /** assess -> (HIGH) review | (default) approve; review -> approve. */
    static PlanModel loan() {
        return PlanModel.builder("loan")
                .task("assess", Split.EXCLUSIVE)
                .task("review")
                .task("approve")
                .edge("assess", "review", "#result['risk'] == 'HIGH'")
                .defaultEdge("assess", "approve")
                .edge("review", "approve")
                .build();
    }

    /** reserve -> fork(pay, ship) -> confirm (join) -> notify. */
    static PlanModel checkout() {
        return PlanModel.builder("checkout")
                .task("reserve", Split.PARALLEL)
                .task("pay")
                .task("ship")
                .join("confirm")
                .task("notify")
                .edge("reserve", "pay")
                .edge("reserve", "ship")
                .edge("pay", "confirm")
                .edge("ship", "confirm")
                .edge("confirm", "notify")
                .build();
    }

    @Test
    void aPlanStartsAtItsOnlyStepWithoutIncomingEdges() {
        assertThat(loan().start()).isEqualTo("assess");
        assertThat(checkout().start()).isEqualTo("reserve");
    }

    @Test
    void keepsTheEdgesLeavingAStepInTheOrderTheyWereDrawn() {
        assertThat(loan().outgoing("assess"))
                .extracting(PlanEdge::to)
                .containsExactly("review", "approve");
        assertThat(loan().outgoing("approve")).isEmpty();
    }

    @Test
    void knowsWhichJoinEachStepBetweenAForkAndItsJoinEndsAt() {
        var plan = checkout();

        assertThat(plan.enclosingJoin("pay")).contains("confirm");
        assertThat(plan.enclosingJoin("ship")).contains("confirm");
        assertThat(plan.enclosingJoin("reserve")).isEmpty();
        assertThat(plan.enclosingJoin("confirm")).isEmpty();
        assertThat(plan.enclosingJoin("notify")).isEmpty();
    }

    @Test
    void aJoinWaitsForOneBranchPerEdgeLeavingItsFork() {
        // Inside a branch, a choice may reach the join along either of two edges; it is still one branch.
        var plan = PlanModel.builder("checkout")
                .task("reserve", Split.PARALLEL)
                .task("pay", Split.EXCLUSIVE)
                .task("card")
                .task("invoice")
                .task("ship")
                .join("confirm")
                .edge("reserve", "pay")
                .edge("reserve", "ship")
                .edge("pay", "card", "#result['method'] == 'card'")
                .defaultEdge("pay", "invoice")
                .edge("card", "confirm")
                .edge("invoice", "confirm")
                .edge("ship", "confirm")
                .build();

        assertThat(plan.expectedBranches("confirm")).isEqualTo(2);
        assertThat(plan.enclosingJoin("card")).contains("confirm");
        assertThat(plan.enclosingJoin("invoice")).contains("confirm");
    }

    @Test
    void carriesTheFlowsJoinTimeoutIfItSetsOne() {
        assertThat(checkout().joinTimeout()).isEmpty();
        var timed = PlanModel.builder("checkout").task("a").joinTimeout(Duration.ofSeconds(30)).build();
        assertThat(timed.joinTimeout()).contains(Duration.ofSeconds(30));
        assertThatThrownBy(() -> PlanModel.builder("checkout").task("a").joinTimeout(Duration.ZERO).build())
                .isInstanceOf(InvalidPlanException.class);
    }

    @Test
    void rejectsStepsAndEdgesThatDoNotFitTogether() {
        assertRejected(PlanModel.builder("f").task("a").task("a"), "step a appears twice");
        assertRejected(PlanModel.builder("f").task("a").edge("a", "b"), "names an unknown step b");
        assertRejected(PlanModel.builder("f").task("a").task("b").edge("a", "b").edge("a", "b"), "a -> b appears twice");
        assertRejected(PlanModel.builder(" ").task("a"), "non-blank name");
        assertRejected(PlanModel.builder("f"), "no steps");
    }

    @Test
    void rejectsAPlanWithoutExactlyOneStart() {
        assertRejected(PlanModel.builder("f").task("a").task("b"), "exactly one start step");
        assertRejected(PlanModel.builder("f").task("a").task("b").edge("a", "b").edge("b", "a"), "exactly one start step");
    }

    @Test
    void acceptsALoopThatAChoiceCanLeave() {
        // draft -> review -> (approved) publish | (default) back to draft
        var plan = PlanModel.builder("f")
                .task("intake").task("draft").task("review", Split.EXCLUSIVE).task("publish")
                .edge("intake", "draft").edge("draft", "review")
                .edge("review", "publish", "#result['approved']").defaultEdge("review", "draft")
                .build();

        assertThat(plan.start()).isEqualTo("intake");
        assertThat(plan.outgoing("review")).extracting(PlanEdge::to).containsExactly("publish", "draft");
    }

    @Test
    void acceptsAStepThatLoopsOnItselfAndALoopInsideABranch() {
        PlanModel.builder("f").task("s").task("poll", Split.EXCLUSIVE).task("done")
                .edge("s", "poll").edge("poll", "poll", "#result['pending']").defaultEdge("poll", "done")
                .build();
        var plan = PlanModel.builder("f").task("a", Split.PARALLEL).task("b", Split.EXCLUSIVE).task("c").join("j")
                .edge("a", "b").edge("a", "c")
                .edge("b", "b", "#result['again']").defaultEdge("b", "j").edge("c", "j")
                .build();

        assertThat(plan.enclosingJoin("b")).contains("j");
        assertThat(plan.expectedBranches("j")).isEqualTo(2);
    }

    @Test
    void rejectsALoopWithNoWayOut() {
        assertRejected(PlanModel.builder("f").task("a").task("b").task("c")
                .edge("a", "b").edge("b", "c").edge("c", "b"), "the loop through [b, c] has no way out");
        // A choice inside the loop whose every edge stays in it is no way out either.
        assertRejected(PlanModel.builder("f").task("a").task("b", Split.EXCLUSIVE).task("c")
                .edge("a", "b").edge("b", "c", "#result['x']").defaultEdge("b", "b").edge("c", "b"),
                "the loop through [b, c] has no way out");
    }

    @Test
    void rejectsAForkOrAJoinInsideALoop() {
        assertRejected(PlanModel.builder("f").task("s").task("a", Split.PARALLEL).task("b").task("c")
                .join("j", Split.EXCLUSIVE).task("end")
                .edge("s", "a").edge("a", "b").edge("a", "c").edge("b", "j").edge("c", "j")
                .edge("j", "a", "#result['again']").defaultEdge("j", "end"), "fork a is inside the loop through [a, b, c, j]");
    }

    @Test
    void rejectsAStartInsideALoopAndStepsThatCannotBeReached() {
        assertRejected(PlanModel.builder("f").task("a").task("b", Split.EXCLUSIVE).task("c")
                .edge("a", "b").edge("b", "a", "#result['x']").defaultEdge("b", "c"), "a flow cannot start inside a loop");
        assertRejected(PlanModel.builder("f").task("s").task("a").task("b", Split.EXCLUSIVE).task("c")
                .edge("a", "b").edge("b", "a", "#result['x']").defaultEdge("b", "c"),
                "steps [a, b, c] cannot be reached from start step s");
    }

    @Test
    void rejectsSplitsThatDoNotMatchTheirEdges() {
        assertRejected(PlanModel.builder("f").task("a").task("b").task("c").edge("a", "b").edge("a", "c"), "step a has 2 edges out");
        assertRejected(PlanModel.builder("f").task("a").task("b").edge("a", "b", "#result['x']"), "step a has a conditional");
        assertRejected(PlanModel.builder("f").task("a", Split.PARALLEL).task("b").join("j")
                .edge("a", "b").edge("b", "j"), "fork a needs at least two branches");
        assertRejected(PlanModel.builder("f").task("a", Split.PARALLEL).task("b").task("c").join("j")
                .edge("a", "b", "#result['x']").edge("a", "c").edge("b", "j").edge("c", "j"), "fork a takes every branch");
        assertRejected(PlanModel.builder("f").task("a", Split.EXCLUSIVE).task("b").task("c")
                .edge("a", "b").edge("a", "c", "#result['x']"), "every edge of choice a needs a condition");
        assertRejected(PlanModel.builder("f").task("a", Split.EXCLUSIVE).task("b").task("c")
                .defaultEdge("a", "b").defaultEdge("a", "c"), "every edge of choice a needs a condition");
    }

    @Test
    void rejectsAForkWhoseBranchesDoNotMeetAtOneJoin() {
        // Branches that never meet would each complete the instance.
        assertRejected(PlanModel.builder("f").task("a", Split.PARALLEL).task("b").task("c")
                .edge("a", "b").edge("a", "c"), "a branch of fork a ends at");
        // Branches that meet at two different joins.
        assertRejected(PlanModel.builder("f").task("a", Split.PARALLEL).task("b").task("c").task("d")
                .join("j").join("k")
                .edge("a", "b").edge("a", "c").edge("a", "d")
                .edge("b", "j").edge("c", "j").edge("d", "k"), "must meet at one join, but meet at [j, k]");
        // A branch with no step of its own.
        assertRejected(PlanModel.builder("f").task("a", Split.PARALLEL).task("b").join("j")
                .edge("a", "b").edge("a", "j").edge("b", "j"), "fork a has a branch with no step before join j");
    }

    @Test
    void rejectsAJoinWithoutAForkAndAStepEnteredFromOutsideItsFork() {
        assertRejected(PlanModel.builder("f").task("a", Split.EXCLUSIVE).task("b").task("c").join("j")
                .edge("a", "b", "#result['x']").defaultEdge("a", "c").edge("b", "j").edge("c", "j"), "join j closes no fork");
        assertRejected(PlanModel.builder("f").task("s", Split.EXCLUSIVE).task("a", Split.PARALLEL)
                .task("b").task("c").join("j")
                .edge("s", "a", "#result['x']").defaultEdge("s", "b")
                .edge("a", "b").edge("a", "c").edge("b", "j").edge("c", "j"), "step b is inside fork a but is entered from s");
    }

    @Test
    void rejectsAForkInsideAnotherFork() {
        assertRejected(PlanModel.builder("f").task("a", Split.PARALLEL).task("b", Split.PARALLEL)
                .task("b1").task("b2").task("c").join("j")
                .edge("a", "b").edge("a", "c").edge("b", "b1").edge("b", "b2")
                .edge("b1", "j").edge("b2", "j").edge("c", "j"), "step b forks inside fork a");
    }

    @Test
    void aServiceMayRunSeveralStepsOfAFlow() {
        var plan = PlanModel.builder("checkout")
                .task("notify-buyer", "notify", Split.NONE)
                .task("charge")
                .task("notify-seller", "notify", Split.NONE)
                .edge("notify-buyer", "charge")
                .edge("charge", "notify-seller")
                .build();

        assertThat(plan.serviceOf("notify-buyer")).isEqualTo("notify");
        assertThat(plan.serviceOf("notify-seller")).isEqualTo("notify");
        assertThat(plan.serviceOf("charge")).isEqualTo("charge");
    }

    private static void assertRejected(PlanModel.Builder plan, String mentioning) {
        assertThatThrownBy(plan::build).isInstanceOf(InvalidPlanException.class).hasMessageContaining(mentioning);
    }
}
