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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A fork whose sibling branch stops once another branch has failed, over the real broker and
 * Redis. One branch fails at its only step; the other has three steps before the join, and the
 * steps it has not started by then are skipped instead of run and undone.
 */
class ForkSkipIT extends AbstractFlowIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    Conditions conditions;

    @Test
    void aSiblingBranchSkipsTheStepsItHadNotStartedWhenTheForkFailedAndOnlyWhatRanIsUndone() throws Exception {
        var reserve = unique("reserve");
        var courier = unique("courier");
        var charge = unique("charge");
        var fraud = unique("fraud");
        var receipt = unique("receipt");
        var ship = unique("ship");
        createTopicsFor(reserve, courier, charge, fraud, receipt, ship);
        var plan = PlanModel.builder("checkout")
                .task(reserve, Split.PARALLEL)
                .task(courier)
                .task(charge)
                .task(fraud)
                .task(receipt)
                .join(ship)
                .edge(reserve, courier)
                .edge(reserve, charge)
                .edge(charge, fraud)
                .edge(fraud, receipt)
                .edge(courier, ship)
                .edge(receipt, ship)
                .joinTimeout(Duration.ofMinutes(1))
                .build();
        var routes = new PlannedRoutes(Plans.of(plan), conditions);
        var fraudChecks = new AtomicInteger();
        var receipts = new AtomicInteger();
        var shipped = new AtomicInteger();
        startStep(reserve, message -> Map.of("quantity", 10), routes);
        // Fails at once; after its retries (about two seconds) it marks the fork failed.
        startStep(courier, message -> {
            throw new IllegalStateException("no courier available");
        }, routes);
        // Still charging when the courier gives up, so the rest of its branch starts too late.
        startStep(charge, message -> {
            sleep(Duration.ofSeconds(5));
            return Map.of("amount", 30);
        }, routes);
        startStep(fraud, message -> Map.of("score", fraudChecks.incrementAndGet()), routes);
        startStep(receipt, message -> Map.of("receipt", receipts.incrementAndGet()), routes);
        startJoin(ship, message -> Map.of("shipped", shipped.incrementAndGet()), routes,
                plan.expectedBranches(ship), plan.joinTimeout().orElseThrow());
        var correlationId = newInstance();

        try (var outcomes = outcomes()) {
            publisher.publish(plan.start(), ChoreographyMessage.start(correlationId, "checkout", Map.of()));

            var compensated = await(outcomes, compensatedTopic, correlationId, WAIT);

            assertThat(outcomeOf(compensated, courier)).isEqualTo(TraceEntry.Outcome.FAILED);
            assertThat(outcomeOf(compensated, charge)).isEqualTo(TraceEntry.Outcome.COMPLETED);
            assertThat(outcomeOf(compensated, fraud)).isEqualTo(TraceEntry.Outcome.SKIPPED);
            // A skipped step goes straight to the join, so the receipt step is never even reached.
            assertThat(compensated.trace()).extracting(TraceEntry::stepId).doesNotContain(receipt);
            assertThat(fraudChecks).hasValue(0);
            assertThat(receipts).hasValue(0);
            assertThat(shipped).hasValue(0);
            // Only what ran is undone, the step before the fork last.
            assertThat(undone).containsExactly(charge, reserve);
            assertThat(more(outcomes, completedTopic, List.of(correlationId), Duration.ofSeconds(1))).isEmpty();
        }
    }

    private static TraceEntry.Outcome outcomeOf(ChoreographyMessage message, String stepId) {
        return message.trace().stream()
                .filter(entry -> entry.stepId().equals(stepId))
                .map(TraceEntry::outcome)
                .findFirst()
                .orElseThrow(() -> new AssertionError(stepId + " is not in the trace"));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
