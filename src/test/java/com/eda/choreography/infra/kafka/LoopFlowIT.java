package com.eda.choreography.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.compensation.CompensationAction;
import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.plan.Conditions;
import com.eda.choreography.domain.plan.PlanModel;
import com.eda.choreography.domain.plan.PlanNode.Split;
import com.eda.choreography.domain.plan.PlannedRoutes;
import com.eda.choreography.domain.plan.Plans;
import com.eda.choreography.domain.step.StepAction;
import com.eda.choreography.domain.trace.TraceEntry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A drawn loop over the real broker: intake, then draft and review until review approves, then
 * publish. Each round adds new entries to the trace, so it stays acyclic, and a failure in any
 * round undoes every round before it, latest first.
 */
class LoopFlowIT extends AbstractFlowIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    Conditions conditions;

    private final Queue<String> undoneEntries = new ConcurrentLinkedQueue<>();

    @Test
    void theLoopGoesRoundUntilReviewApprovesAndThenTheFlowCompletes() throws Exception {
        var flow = new Flow(review -> Map.of("visits", review.runsOf(review.step()) + 1), 100);
        var correlationId = newInstance();

        try (var outcomes = outcomes()) {
            flow.start(correlationId);

            var done = await(outcomes, completedTopic, correlationId, WAIT);

            assertThat(done.trace()).extracting(TraceEntry::stepId).containsExactly(
                    flow.intake, flow.draft, flow.review, flow.draft, flow.review, flow.draft, flow.review, flow.publish);
            assertThat(done.runsOf(flow.draft)).isEqualTo(3);
            assertThat(undoneEntries).isEmpty();
        }
    }

    @Test
    void aFailureInTheSecondRoundUndoesBothRoundsLatestFirst() throws Exception {
        var flow = new Flow(review -> {
            if (review.runsOf(review.step()) == 1) {
                throw new IllegalStateException("reviewer gone");
            }
            return Map.of("visits", 1);
        }, 100);
        var correlationId = newInstance();

        try (var outcomes = outcomes()) {
            flow.start(correlationId);

            var compensated = await(outcomes, compensatedTopic, correlationId, WAIT);

            assertThat(compensated.trace()).extracting(TraceEntry::stepId)
                    .containsExactly(flow.intake, flow.draft, flow.review, flow.draft, flow.review);
            assertThat(compensated.trace().get(4).outcome()).isEqualTo(TraceEntry.Outcome.FAILED);
            assertThat(List.copyOf(undoneEntries)).containsExactly(completedLatestFirst(compensated));
        }
    }

    @Test
    void aLoopThatNeverLeavesFailsAtItsLimitAndIsUndone() throws Exception {
        var flow = new Flow(review -> Map.of("visits", 0), 2);
        var correlationId = newInstance();

        try (var outcomes = outcomes()) {
            flow.start(correlationId);

            var compensated = await(outcomes, compensatedTopic, correlationId, WAIT);

            assertThat(compensated.trace()).extracting(TraceEntry::stepId).containsExactly(
                    flow.intake, flow.draft, flow.review, flow.draft, flow.review, flow.draft);
            assertThat(compensated.trace().get(5).outcome()).isEqualTo(TraceEntry.Outcome.FAILED);
            assertThat(List.copyOf(undoneEntries)).containsExactly(completedLatestFirst(compensated));
            assertThat(more(outcomes, completedTopic, List.of(correlationId), Duration.ofSeconds(1))).isEmpty();
        }
    }

    /** On a linear trace, undoing in reverse causal order is undoing in reverse trace order. */
    private static String[] completedLatestFirst(ChoreographyMessage instance) {
        var latestFirst = new ArrayList<>(instance.trace());
        Collections.reverse(latestFirst);
        return latestFirst.stream().filter(TraceEntry::needsCompensation).map(TraceEntry::id).toArray(String[]::new);
    }

    /** intake, then draft and review until review's visits reach 3, then publish. */
    private final class Flow {

        final String intake = unique("intake");
        final String draft = unique("draft");
        final String review = unique("review");
        final String publish = unique("publish");
        private final PlanModel plan;

        Flow(StepAction reviewing, int maxRuns) throws Exception {
            createTopicsFor(intake, draft, review, publish);
            plan = PlanModel.builder("drafting")
                    .task(intake)
                    .task(draft)
                    .task(review, Split.EXCLUSIVE)
                    .task(publish)
                    .edge(intake, draft)
                    .edge(draft, review)
                    .edge(review, publish, "#result['visits'] >= 3")
                    .defaultEdge(review, draft)
                    .maxRuns(maxRuns)
                    .build();
            var routes = new PlannedRoutes(Plans.of(plan), conditions);
            CompensationAction recordEntry = (entry, instance) -> undoneEntries.add(entry.id());
            startStep(intake, message -> Map.of("topic", "loops"), routes, recordEntry);
            startStep(draft, message -> Map.of("words", 100), routes, recordEntry);
            startStep(review, reviewing, routes, recordEntry);
            startStep(publish, message -> Map.of("published", true), routes, recordEntry);
        }

        void start(String correlationId) {
            publisher.publish(intake, ChoreographyMessage.start(correlationId, "drafting", Map.of()));
        }
    }
}
