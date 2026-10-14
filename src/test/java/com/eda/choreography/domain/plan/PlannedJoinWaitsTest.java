package com.eda.choreography.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.plan.PlanNode.Split;
import com.eda.choreography.domain.step.JoinWait;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PlannedJoinWaitsTest {

    private static final PlanModel RETURNS = PlanModel.builder("returns")
            .task("receive", Split.PARALLEL)
            .task("inspect")
            .task("refund")
            .task("restock")
            .join("confirm")
            .edge("receive", "inspect")
            .edge("receive", "refund")
            .edge("receive", "restock")
            .edge("inspect", "confirm")
            .edge("refund", "confirm")
            .edge("restock", "confirm")
            .joinTimeout(Duration.ofSeconds(90))
            .build();

    private final PlannedJoinWaits waits =
            new PlannedJoinWaits(Plans.of(RETURNS, PlanModelTest.checkout()), Duration.ofMinutes(5));

    @Test
    void aJoinWaitsForItsForksBranchesForTheFlowsTimeout() {
        assertThat(waits.forJoin("returns", "confirm")).isEqualTo(new JoinWait(3, Duration.ofSeconds(90)));
    }

    @Test
    void aFlowThatSetsNoTimeoutGetsTheDefault() {
        assertThat(waits.forJoin("checkout", "confirm")).isEqualTo(new JoinWait(2, Duration.ofMinutes(5)));
    }

    @Test
    void knowsWhichStepsOfAFlowAreJoins() {
        assertThat(waits.isJoin("returns", "confirm")).isTrue();
        assertThat(waits.isJoin("returns", "refund")).isFalse();
        assertThat(waits.isJoin("unknown", "confirm")).isFalse();
    }

    @Test
    void aFlowWithoutAPlanCannotBeJoined() {
        assertThatThrownBy(() -> waits.forJoin("unknown", "confirm")).isInstanceOf(NoRouteException.class);
    }
}
