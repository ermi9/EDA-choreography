package com.eda.choreography.infra.bpmn;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.plan.PlanNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
