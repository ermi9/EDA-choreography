package com.eda.choreography.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.plan.Conditions;
import com.eda.choreography.domain.plan.PlanModel;
import com.eda.choreography.domain.plan.PlanNode.Split;
import com.eda.choreography.domain.plan.PlannedRoutes;
import com.eda.choreography.domain.plan.Plans;
import com.eda.choreography.domain.trace.TraceEntry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Flows routed by their plan over the real broker and Redis. Each step picks its next hop from
 * the plan and its own result (conditions in SpEL), and reaches the next service through the
 * topics discovery's stream gave it. There is no hand-written routing.
 */
class PlannedFlowIT extends AbstractFlowIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    Conditions conditions;

    @Test
    void theSameAssessmentSendsAHighRiskLoanToReviewAndALowRiskOneStraightToApproval() throws Exception {
        var assess = unique("assess");
        var review = unique("review");
        var approve = unique("approve");
        createTopicsFor(assess, review, approve);
        var plan = PlanModel.builder("loan")
                .task(assess, Split.EXCLUSIVE)
                .task(review)
                .task(approve)
                .edge(assess, review, "#result['risk'] == 'HIGH'")
                .defaultEdge(assess, approve)
                .edge(review, approve)
                .build();
        var routes = new PlannedRoutes(Plans.of(plan), conditions);
        var reviewed = ConcurrentHashMap.<String>newKeySet();
        startStep(assess, message -> Map.of("risk", amount(message) > 1000 ? "HIGH" : "LOW"), routes);
        startStep(review, message -> {
            reviewed.add(message.correlationId());
            return Map.of("reviewer", "ana");
        }, routes);
        startStep(approve, message -> Map.of("approved", true), routes);
        var large = newInstance();
        var small = newInstance();

        try (var outcomes = outcomes()) {
            publisher.publish(plan.start(), ChoreographyMessage.start(large, "loan", Map.of("amount", 5000)));
            publisher.publish(plan.start(), ChoreographyMessage.start(small, "loan", Map.of("amount", 200)));

            // Both at once: waiting for one instance would skip past the other's outcome.
            var done = collect(outcomes, completedTopic, List.of(large, small), 2, WAIT).stream()
                    .collect(Collectors.toMap(ChoreographyMessage::correlationId, Function.identity()));
            assertThat(done).containsOnlyKeys(large, small);
            var high = done.get(large);
            var low = done.get(small);

            assertThat(high.trace()).extracting(TraceEntry::stepId).containsExactly(assess, review, approve);
            assertThat(high.resultOf(assess)).contains(Map.of("risk", "HIGH"));
            assertThat(low.trace()).extracting(TraceEntry::stepId).containsExactly(assess, approve);
            assertThat(low.resultOf(assess)).contains(Map.of("risk", "LOW"));
            assertThat(reviewed).containsExactly(large);
        }
    }

    @Test
    void aPlannedForkFeedsTheJoinThatClosesItAndTheFlowGoesOnFromThere() throws Exception {
        var reserve = unique("reserve");
        var pay = unique("pay");
        var ship = unique("ship");
        var confirm = unique("confirm");
        var notify = unique("notify");
        createTopicsFor(reserve, pay, ship, confirm, notify);
        var plan = checkout(reserve, pay, ship, confirm, notify);
        var routes = new PlannedRoutes(Plans.of(plan), conditions);
        startStep(reserve, message -> Map.of("quantity", 10), routes);
        startStep(pay, message -> Map.of("amount", 30), routes);
        startStep(ship, message -> Map.of("slot", "monday"), routes);
        startJoin(confirm, message -> Map.of("confirmed", true), routes,
                plan.expectedBranches(confirm), plan.joinTimeout().orElseThrow());
        startStep(notify, message -> Map.of("sent", true), routes);
        var correlationId = newInstance();

        try (var outcomes = outcomes()) {
            publisher.publish(plan.start(), ChoreographyMessage.start(correlationId, "checkout", Map.of()));

            var done = await(outcomes, completedTopic, correlationId, WAIT);

            assertThat(done.resultOf(pay)).contains(Map.of("amount", 30));
            assertThat(done.resultOf(ship)).contains(Map.of("slot", "monday"));
            assertThat(done.resultOf(notify)).contains(Map.of("sent", true));
            assertThat(entry(done, confirm).parents()).containsExactlyInAnyOrder(entry(done, pay).id(), entry(done, ship).id());
            assertThat(entry(done, notify).parents()).containsExactly(entry(done, confirm).id());
            assertThat(more(outcomes, completedTopic, List.of(correlationId), Duration.ofSeconds(1))).isEmpty();
        }
    }

    @Test
    void aBranchOfAPlannedForkThatKeepsFailingFindsItsJoinFromThePlanAndEverythingIsUndone() throws Exception {
        var reserve = unique("reserve");
        var pay = unique("pay");
        var ship = unique("ship");
        var confirm = unique("confirm");
        var notify = unique("notify");
        createTopicsFor(reserve, pay, ship, confirm, notify);
        var plan = checkout(reserve, pay, ship, confirm, notify);
        var routes = new PlannedRoutes(Plans.of(plan), conditions);
        var confirmed = new AtomicInteger();
        startStep(reserve, message -> Map.of("quantity", 10), routes);
        startStep(pay, message -> Map.of("amount", 30), routes);
        startStep(ship, message -> {
            throw new IllegalStateException("shipping service down");
        }, routes);
        startJoin(confirm, message -> Map.of("confirmed", confirmed.incrementAndGet()), routes,
                plan.expectedBranches(confirm), plan.joinTimeout().orElseThrow());
        startStep(notify, message -> Map.of("sent", true), routes);
        var correlationId = newInstance();

        try (var outcomes = outcomes()) {
            publisher.publish(plan.start(), ChoreographyMessage.start(correlationId, "checkout", Map.of()));

            var compensated = await(outcomes, compensatedTopic, correlationId, WAIT);

            assertThat(undone).containsExactly(pay, reserve);
            assertThat(confirmed).hasValue(0);
            assertThat(compensated.hasFailed()).isTrue();
            assertThat(more(outcomes, completedTopic, List.of(correlationId), Duration.ofSeconds(1))).isEmpty();
        }
    }

    /** reserve, then pay and ship in parallel, joined at confirm, then notify. */
    private static PlanModel checkout(String reserve, String pay, String ship, String confirm, String notify) {
        return PlanModel.builder("checkout")
                .task(reserve, Split.PARALLEL)
                .task(pay)
                .task(ship)
                .join(confirm)
                .task(notify)
                .edge(reserve, pay)
                .edge(reserve, ship)
                .edge(pay, confirm)
                .edge(ship, confirm)
                .edge(confirm, notify)
                .joinTimeout(Duration.ofMinutes(1))
                .build();
    }

    private static int amount(ChoreographyMessage message) {
        return ((Number) message.input().get("amount")).intValue();
    }

    private static TraceEntry entry(ChoreographyMessage message, String stepId) {
        return message.trace().stream().filter(entry -> entry.stepId().equals(stepId)).findFirst().orElseThrow();
    }
}
