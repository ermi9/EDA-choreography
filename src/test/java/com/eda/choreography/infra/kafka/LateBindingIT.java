package com.eda.choreography.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.trace.TraceEntry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

/**
 * A step whose next service discovery does not know yet waits for it (3 s in these tests), and
 * hands on if it appears in time. If it never does, the next step counts as failed and the step
 * that ran, whose work is committed, is undone.
 */
class LateBindingIT extends AbstractFlowIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Test
    void aServiceThatRegistersWhileTheStepBeforeItWaitsGetsTheWork() throws Exception {
        var quote = unique("quote");
        var underwrite = unique("underwrite");
        createTopicsFor(quote);
        createTopicsWithoutRegistering(underwrite);
        var routes = new Routes().then(quote, underwrite);
        startStep(quote, message -> Map.of("premium", 120), routes);
        startStep(underwrite, message -> Map.of("accepted", true), routes);
        var correlationId = newInstance();

        try (var outcomes = outcomes()) {
            publisher.publish(quote, ChoreographyMessage.start(correlationId, "policy", Map.of()));
            Thread.sleep(1_000);
            registerWithDiscovery(underwrite);

            var done = await(outcomes, completedTopic, correlationId, WAIT);

            assertThat(done.resultOf(underwrite)).contains(Map.of("accepted", true));
            assertThat(undone).isEmpty();
        }
    }

    @Test
    void aServiceThatNeverAppearsFailsItsStepAndTheStepThatRanBeforeItIsUndone() throws Exception {
        var quote = unique("quote");
        var underwrite = unique("underwrite");
        createTopicsFor(quote);
        createTopicsWithoutRegistering(underwrite);
        var routes = new Routes().then(quote, underwrite);
        startStep(quote, message -> Map.of("premium", 120), routes);
        var correlationId = newInstance();

        try (var outcomes = outcomes()) {
            publisher.publish(quote, ChoreographyMessage.start(correlationId, "policy", Map.of()));

            var compensated = await(outcomes, compensatedTopic, correlationId, WAIT);

            assertThat(undone).containsExactly(quote);
            assertThat(compensated.trace()).extracting(TraceEntry::stepId, TraceEntry::outcome).containsExactly(
                    Tuple.tuple(quote, TraceEntry.Outcome.COMPLETED),
                    Tuple.tuple(underwrite, TraceEntry.Outcome.FAILED));
            assertThat(more(outcomes, completedTopic, List.of(correlationId), Duration.ofSeconds(1))).isEmpty();
        }
    }
}
