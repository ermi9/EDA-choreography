package com.eda.choreography.infra.bpmn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.plan.InvalidPlanException;
import com.eda.choreography.domain.plan.PlanEdge;
import com.eda.choreography.domain.plan.PlanModel;
import com.eda.choreography.domain.plan.PlanNode;
import com.eda.choreography.domain.plan.PlanNode.Split;
import java.io.IOException;
import java.time.Duration;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BpmnPlannerTest {

    private final BpmnPlanner planner = new BpmnPlanner();

    @Test
    void aTaskBeforeAnExclusiveGatewayBecomesAChoiceWithItsConditionsAsWrittenAndItsDefault() {
        var plan = planner.plan(fixture("loan"));

        assertThat(plan.flowName()).isEqualTo("loan");
        assertThat(plan.start()).isEqualTo("assess");
        assertThat(plan.node("assess")).map(PlanNode::split).contains(Split.EXCLUSIVE);
        assertThat(plan.outgoing("assess")).containsExactly(
                new PlanEdge("assess", "review", "#result['risk'] == 'HIGH'", false),
                new PlanEdge("assess", "approve", null, true));
        assertThat(plan.outgoing("review")).containsExactly(new PlanEdge("review", "approve", null, false));
        assertThat(plan.outgoing("approve")).isEmpty();
    }

    @Test
    void aServiceThatRunsTwoTasksNamesItsStepsAfterTheTasksAndCompensationTasksAreLeftOut() {
        var plan = planner.plan(fixture("notify-twice"));

        assertThat(plan.start()).isEqualTo("Task_buyer");
        assertThat(plan.serviceOf("Task_buyer")).isEqualTo("notify");
        assertThat(plan.serviceOf("Task_seller")).isEqualTo("notify");
        assertThat(plan.serviceOf("charge")).isEqualTo("charge");
        assertThat(plan.services()).containsExactly("notify", "charge");
        assertThat(plan.outgoing("charge")).extracting(PlanEdge::to).containsExactly("Task_seller");
    }

    @Test
    void anExclusiveGatewayThatOnlyMergesPathsIsPassedThrough() {
        var plan = planner.plan(new BpmnFlow("merging", process("""
                <bpmn:startEvent id="s"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:serviceTask id="a" name="a"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:serviceTask>
                <bpmn:exclusiveGateway id="merge"><bpmn:incoming>f2</bpmn:incoming><bpmn:outgoing>f3</bpmn:outgoing></bpmn:exclusiveGateway>
                <bpmn:serviceTask id="b" name="b"><bpmn:incoming>f3</bpmn:incoming><bpmn:outgoing>f4</bpmn:outgoing></bpmn:serviceTask>
                <bpmn:endEvent id="e"><bpmn:incoming>f4</bpmn:incoming></bpmn:endEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="a" />
                <bpmn:sequenceFlow id="f2" sourceRef="a" targetRef="merge" />
                <bpmn:sequenceFlow id="f3" sourceRef="merge" targetRef="b" />
                <bpmn:sequenceFlow id="f4" sourceRef="b" targetRef="e" />
                """)));

        assertThat(plan.outgoing("a")).containsExactly(new PlanEdge("a", "b", null, false));
    }

    @Test
    void parallelGatewaysBecomeAForkAndTheJoinStepAfterWhereTheBranchesMeet() {
        var plan = planner.plan(fixture("checkout"));

        assertThat(plan.start()).isEqualTo("reserve");
        assertThat(plan.node("reserve")).map(PlanNode::split).contains(Split.PARALLEL);
        assertThat(plan.outgoing("reserve")).extracting(PlanEdge::to).containsExactly("pay", "ship");
        assertThat(plan.node("confirm")).map(PlanNode::kind).contains(PlanNode.Kind.JOIN);
        assertThat(plan.expectedBranches("confirm")).isEqualTo(2);
        assertThat(plan.enclosingJoin("pay")).contains("confirm");
        assertThat(plan.enclosingJoin("ship")).contains("confirm");
        assertThat(plan.joinTimeout()).contains(Duration.ofSeconds(45));
    }

    @Test
    void rejectsAJoinThatLeadsNowhereAndATimeoutThatIsNotADuration() {
        var fixture = fixture("checkout").xml();
        assertRejected(fixture.replace("choreo:joinTimeout=\"PT45S\"", "choreo:joinTimeout=\"45 seconds\""),
                "'45 seconds' is not an ISO-8601 duration");
        assertRejected(fixture.replace("<bpmn:sequenceFlow id=\"f7\" sourceRef=\"join\" targetRef=\"Task_confirm\" />",
                        "<bpmn:sequenceFlow id=\"f7\" sourceRef=\"join\" targetRef=\"end\" />"),
                "must lead straight to a task");
    }

    @Test
    void rejectsAJoinStepWhoseServiceAlsoRunsAnotherTask() {
        var fixture = fixture("checkout").xml();
        assertRejected(fixture.replace("name=\"confirm\"", "name=\"reserve\""), "joins branches");
    }

    @Test
    void rejectsWhatIsNotBpmn() {
        assertRejected("<not-bpmn/>", "not valid BPMN");
        assertRejected("this is not xml", "not valid BPMN");
    }

    @Test
    void rejectsADiagramWithoutOneStartEvent() {
        assertRejected(process("""
                <bpmn:serviceTask id="a" name="a" />
                """), "no start event");
        assertRejected(process("""
                <bpmn:startEvent id="s1" /><bpmn:startEvent id="s2" />
                <bpmn:serviceTask id="a" name="a" />
                """), "more than one start event");
    }

    @Test
    void rejectsElementsItDoesNotSupport() {
        assertRejected(process("""
                <bpmn:startEvent id="s"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:serviceTask id="a" name="a"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:serviceTask>
                <bpmn:inclusiveGateway id="g"><bpmn:incoming>f2</bpmn:incoming></bpmn:inclusiveGateway>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="a" />
                <bpmn:sequenceFlow id="f2" sourceRef="a" targetRef="g" />
                """), "inclusiveGateway g is not supported");
    }

    @Test
    void rejectsATaskThatLeavesAlongTwoFlowsWithoutAGateway() {
        assertRejected(process("""
                <bpmn:startEvent id="s"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:serviceTask id="a" name="a"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing><bpmn:outgoing>f3</bpmn:outgoing></bpmn:serviceTask>
                <bpmn:serviceTask id="b" name="b"><bpmn:incoming>f2</bpmn:incoming><bpmn:outgoing>f4</bpmn:outgoing></bpmn:serviceTask>
                <bpmn:serviceTask id="c" name="c"><bpmn:incoming>f3</bpmn:incoming><bpmn:outgoing>f5</bpmn:outgoing></bpmn:serviceTask>
                <bpmn:endEvent id="e"><bpmn:incoming>f4</bpmn:incoming><bpmn:incoming>f5</bpmn:incoming></bpmn:endEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="a" />
                <bpmn:sequenceFlow id="f2" sourceRef="a" targetRef="b" />
                <bpmn:sequenceFlow id="f3" sourceRef="a" targetRef="c" />
                <bpmn:sequenceFlow id="f4" sourceRef="b" targetRef="e" />
                <bpmn:sequenceFlow id="f5" sourceRef="c" targetRef="e" />
                """), "task a has 2 outgoing flows");
    }

    @Test
    void rejectsAChoiceFlowWithoutAConditionThatIsNotTheDefault() {
        assertRejected(process("""
                <bpmn:startEvent id="s"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:serviceTask id="a" name="a"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:serviceTask>
                <bpmn:exclusiveGateway id="g"><bpmn:incoming>f2</bpmn:incoming><bpmn:outgoing>f3</bpmn:outgoing><bpmn:outgoing>f4</bpmn:outgoing></bpmn:exclusiveGateway>
                <bpmn:serviceTask id="b" name="b"><bpmn:incoming>f3</bpmn:incoming><bpmn:outgoing>f5</bpmn:outgoing></bpmn:serviceTask>
                <bpmn:serviceTask id="c" name="c"><bpmn:incoming>f4</bpmn:incoming><bpmn:outgoing>f6</bpmn:outgoing></bpmn:serviceTask>
                <bpmn:endEvent id="e"><bpmn:incoming>f5</bpmn:incoming><bpmn:incoming>f6</bpmn:incoming></bpmn:endEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="a" />
                <bpmn:sequenceFlow id="f2" sourceRef="a" targetRef="g" />
                <bpmn:sequenceFlow id="f3" sourceRef="g" targetRef="b">
                  <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">#result['ok']</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="f4" sourceRef="g" targetRef="c" />
                <bpmn:sequenceFlow id="f5" sourceRef="b" targetRef="e" />
                <bpmn:sequenceFlow id="f6" sourceRef="c" targetRef="e" />
                """), "the flow f4 out of choice g needs a condition");
    }

    @Test
    void rejectsAServiceNameThatCannotBeATopic() {
        assertRejected(process("""
                <bpmn:startEvent id="s"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:serviceTask id="a" name="Check stock"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:serviceTask>
                <bpmn:endEvent id="e"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="a" />
                <bpmn:sequenceFlow id="f2" sourceRef="a" targetRef="e" />
                """), "service 'Check stock'");
    }

    @Test
    void aPlanThatCannotRunAsDrawnIsRejectedByThePlanModel() {
        // A task nothing leads to is a second start.
        assertRejected(process("""
                <bpmn:startEvent id="s"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:serviceTask id="a" name="a"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:serviceTask>
                <bpmn:serviceTask id="b" name="b"><bpmn:outgoing>f3</bpmn:outgoing></bpmn:serviceTask>
                <bpmn:endEvent id="e"><bpmn:incoming>f2</bpmn:incoming><bpmn:incoming>f3</bpmn:incoming></bpmn:endEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="a" />
                <bpmn:sequenceFlow id="f2" sourceRef="a" targetRef="e" />
                <bpmn:sequenceFlow id="f3" sourceRef="b" targetRef="e" />
                """), "exactly one start step");
    }

    private void assertRejected(String xml, String mentioning) {
        assertThatThrownBy(() -> planner.plan(new BpmnFlow("drawn", xml)))
                .isInstanceOf(InvalidPlanException.class)
                .hasMessageContaining(mentioning);
    }

    static String process(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                  id="d" targetNamespace="http://eda.com/flows">
                  <bpmn:process id="p" isExecutable="true">
                """ + body + """
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }

    static BpmnFlow fixture(String name) {
        try (var in = BpmnPlannerTest.class.getResourceAsStream("/bpmn/" + name + ".bpmn")) {
            return new BpmnFlow(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static PlanModel plan(String fixture) {
        return new BpmnPlanner().plan(fixture(fixture));
    }
}
