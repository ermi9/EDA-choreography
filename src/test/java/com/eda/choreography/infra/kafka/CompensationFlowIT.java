package com.eda.choreography.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.trace.TraceEntry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A step that keeps failing gets the instance undone, over the real broker, with no coordinator. */
class CompensationFlowIT extends AbstractFlowIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Test
    void aStepThatKeepsFailingUndoesTheStepsBeforeItNewestFirst() throws Exception {
        var reserve = unique("reserve");
        var price = unique("price");
        var tax = unique("tax");
        createTopicsFor(reserve, price, tax);
        var routes = new Routes().then(reserve, price).then(price, tax);
        var attempts = new AtomicInteger();
        startStep(reserve, message -> Map.of("quantity", 10), routes);
        startStep(price, message -> Map.of("amount", 30), routes);
        startStep(tax, message -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("tax service down");
        }, routes);
        var correlationId = newInstance();

        try (var outcomes = outcomes()) {
            publisher.publish(reserve, ChoreographyMessage.start(correlationId, "checkout", Map.of()));

            var compensated = await(outcomes, compensatedTopic, correlationId, WAIT);

            assertThat(attempts).hasValue(3);
            assertThat(undone).containsExactly(price, reserve);
            assertThat(compensated.trace()).extracting(TraceEntry::stepId).containsExactlyInAnyOrder(reserve, price, tax);
            assertThat(compensated.trace()).filteredOn(entry -> entry.stepId().equals(tax))
                    .singleElement().extracting(TraceEntry::outcome).isEqualTo(TraceEntry.Outcome.FAILED);
            assertThat(more(outcomes, completedTopic, List.of(correlationId), Duration.ofSeconds(1))).isEmpty();
        }
    }

    @Test
    void aBranchThatKeepsFailingWaitsForItsSiblingAndThenEverythingIsUndone() throws Exception {
        var a = unique("a");
        var b = unique("b");
        var c = unique("c");
        var join = unique("join");
        createTopicsFor(a, b, c, join);
        var routes = new Routes().then(a, b, c).then(b, join).then(c, join).joinedAt(join, b, c);
        var joined = new AtomicInteger();
        startStep(a, message -> Map.of("quantity", 10), routes);
        startStep(b, message -> Map.of("amount", 30), routes);
        startStep(c, message -> {
            throw new IllegalStateException("shipping service down");
        }, routes);
        startJoin(join, message -> Map.of("joined", joined.incrementAndGet()), routes, 2, Duration.ofMinutes(1));
        var correlationId = newInstance();

        try (var outcomes = outcomes()) {
            publisher.publish(a, ChoreographyMessage.start(correlationId, "checkout", Map.of()));

            var compensated = await(outcomes, compensatedTopic, correlationId, WAIT);

            assertThat(undone).containsExactly(b, a);
            assertThat(joined).hasValue(0);
            assertThat(compensated.hasFailed()).isTrue();
            assertThat(more(outcomes, completedTopic, List.of(correlationId), Duration.ofSeconds(1))).isEmpty();
        }
    }
}
