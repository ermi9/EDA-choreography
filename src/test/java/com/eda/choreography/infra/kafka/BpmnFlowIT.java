package com.eda.choreography.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.join.JoinStateMachine;
import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.plan.Conditions;
import com.eda.choreography.domain.plan.PlannedJoinWaits;
import com.eda.choreography.domain.plan.PlannedRoutes;
import com.eda.choreography.domain.plan.Plans;
import com.eda.choreography.domain.plan.ServiceBindings;
import com.eda.choreography.domain.step.JoinRunner;
import com.eda.choreography.domain.step.StepRunner;
import com.eda.choreography.domain.trace.TraceEntry;
import com.eda.choreography.infra.bpmn.BpmnFlow;
import com.eda.choreography.infra.bpmn.BpmnPlanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A drawn BPMN flow, translated once by the planner, runs on the runtime over the real broker
 * and Redis: a choice on a step's result, then a fork joined before the last step.
 */
class BpmnFlowIT extends AbstractFlowIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    Conditions conditions;

    @Test
    void aDrawnFlowWithAChoiceAndAForkRunsAsDrawn() throws Exception {
        var suffix = unique("");
        var assess = "assess" + suffix;
        var review = "review" + suffix;
        var reserve = "reserve" + suffix;
        var pay = "pay" + suffix;
        var ship = "ship" + suffix;
        var confirm = "confirm" + suffix;
        var drawn = fixture("order");
        for (var service : List.of("assess", "review", "reserve", "pay", "ship", "confirm")) {
            drawn = drawn.replace("name=\"" + service + "\"", "name=\"" + service + suffix + "\"");
        }
        var plan = new BpmnPlanner().plan(new BpmnFlow("order", drawn));
        createTopicsFor(assess, reserve, pay, ship, confirm);
        createTopicsWithoutRegistering(review);

        // review is drawn, but discovery does not know it yet: the flow is accepted with it late-bound.
        var bindings = ServiceBindings.of(plan, serviceTable);
        assertThat(bindings.lateBound()).containsExactly(review);
        registerWithDiscovery(review);
        assertThat(ServiceBindings.of(plan, serviceTable).fullyBound()).isTrue();

        var plans = Plans.of(plan);
        var routes = new PlannedRoutes(plans, conditions);
        startStep(assess, message -> Map.of("risk", ((Number) message.input().get("amount")).intValue() > 1000 ? "HIGH" : "LOW"), routes);
        startStep(review, message -> Map.of("reviewer", "ana"), routes);
        startStep(reserve, message -> Map.of("quantity", 1), routes);
        startStep(pay, message -> Map.of("amount", 30), routes);
        startStep(ship, message -> Map.of("slot", "monday"), routes);
        start(containers.create(new JoinRunner(
                new StepRunner(confirm, message -> Map.of("confirmed", true), routes, publisher, compensations, serviceTable::isLive),
                new PlannedJoinWaits(plans, Duration.ofMinutes(5)),
                new JoinStateMachine(joinStore), deadlines, compensations, Clock.systemUTC())));
        startCompensator(confirm);
        var large = newInstance();
        var small = newInstance();

        try (var outcomes = outcomes()) {
            publisher.publish(plan.start(), ChoreographyMessage.start(large, "order", Map.of("amount", 5000)));
            publisher.publish(plan.start(), ChoreographyMessage.start(small, "order", Map.of("amount", 20)));

            var done = collect(outcomes, completedTopic, List.of(large, small), 2, WAIT).stream()
                    .collect(Collectors.toMap(ChoreographyMessage::correlationId, Function.identity()));

            assertThat(done).containsOnlyKeys(large, small);
            assertThat(steps(done.get(large))).containsExactlyInAnyOrder(assess, review, reserve, pay, ship, confirm);
            assertThat(steps(done.get(small))).containsExactlyInAnyOrder(assess, reserve, pay, ship, confirm);
            var joined = done.get(small).trace().stream().filter(entry -> entry.stepId().equals(confirm)).findFirst().orElseThrow();
            assertThat(joined.parents()).hasSize(2);
            assertThat(undone).isEmpty();
        }
    }

    private static List<String> steps(ChoreographyMessage message) {
        return message.trace().stream().map(TraceEntry::stepId).toList();
    }

    private static String fixture(String name) throws IOException {
        try (var in = BpmnFlowIT.class.getResourceAsStream("/bpmn/" + name + ".bpmn")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
