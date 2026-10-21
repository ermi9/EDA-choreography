package com.eda.choreography.infra.bpmn;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.plan.PlanEdge;
import com.eda.choreography.domain.plan.PlanNode;
import com.eda.choreography.infra.plan.SpelConditions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The demo flows shipped in {@code deploy/flows} translate, and do what their names promise. */
class DeployedFlowsTest {

    private final BpmnPlanner planner = new BpmnPlanner();

    @Test
    void theOrderFlowChoosesOnTheAmountAndJoinsItsTwoBranches() throws IOException {
        var plan = planner.plan(new BpmnFlow("order", Files.readString(Path.of("deploy/flows/order.bpmn"))));

        assertThat(plan.outgoing("assess")).first().satisfies(edge -> assertThat(edge.condition()).isEqualTo("#result['amount'] > 1000"));
        assertThat(plan.node("confirm")).map(PlanNode::kind).contains(PlanNode.Kind.JOIN);
        assertThat(plan.expectedBranches("confirm")).isEqualTo(2);
    }

    @Test
    void theSaleFlowRunsTheNotifyServiceTwice() throws IOException {
        var plan = planner.plan(new BpmnFlow("sale", Files.readString(Path.of("deploy/flows/sale.bpmn"))));

        assertThat(plan.services()).containsExactly("notify", "charge");
        assertThat(plan.start()).isEqualTo("Task_buyer");
    }

    @Test
    void theRetryLoopGoesRoundUntilReviewHasRunTheRequestedRounds() throws IOException {
        var plan = planner.plan(new BpmnFlow("retry-loop", Files.readString(Path.of("deploy/flows/retry-loop.bpmn"))));
        var leave = plan.outgoing("review").get(0);
        var conditions = new SpelConditions();

        assertThat(plan.outgoing("review")).extracting(PlanEdge::to).containsExactly("notify", "reserve");
        assertThat(plan.maxRuns()).contains(4);
        assertThat(conditions.holds(leave.condition(), Map.of("visits", 2, "rounds", 3))).isFalse();
        assertThat(conditions.holds(leave.condition(), Map.of("visits", 3, "rounds", 3))).isTrue();
        assertThat(conditions.holds(leave.condition(), Map.of("visits", 3))).isTrue();
    }
}
