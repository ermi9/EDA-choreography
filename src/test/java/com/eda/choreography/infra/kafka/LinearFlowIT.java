package com.eda.choreography.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.compensation.CompensationOrdering;
import com.eda.choreography.domain.compensation.CompensationTrigger;
import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.step.LinearFlow;
import com.eda.choreography.domain.step.MessagePublisher;
import com.eda.choreography.domain.step.StepAction;
import com.eda.choreography.domain.step.StepRunner;
import com.eda.choreography.domain.trace.TraceEntry;
import com.eda.choreography.domain.trace.TraceGraph;
import com.eda.choreography.infra.AbstractInfraIT;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;

/**
 * The walking skeleton: a hardcoded three-step linear flow runs end to end over a real broker.
 * Each step runs in its own listener container, as it would in its own service, and the only
 * thing passing between them is the message on Kafka.
 */
class LinearFlowIT extends AbstractInfraIT {

    private static final LinearFlow CHECKOUT = LinearFlow.of("checkout", "reserve", "price", "tax");

    /** Each step works only from the request and what earlier steps put in the message. */
    private static final Map<String, StepAction> ACTIONS = Map.of(
            "reserve", message -> Map.of("quantity", message.input().get("quantity")),
            "price", message -> Map.of("amount", number(message, "reserve", "quantity") * 3),
            "tax", message -> Map.of("total", number(message, "price", "amount") * 11 / 10));

    @Autowired
    StepContainerFactory containers;

    @Autowired
    MessagePublisher publisher;

    @Autowired
    CompensationTrigger compensations;

    @Autowired
    ConsumerFactory<String, ChoreographyMessage> consumerFactory;

    @Value("${choreography.kafka.completed-topic}")
    String completedTopic;

    private final List<MessageListenerContainer> running = new ArrayList<>();

    @AfterEach
    void stopSteps() {
        running.forEach(MessageListenerContainer::stop);
    }

    @Test
    void aLinearFlowRunsEndToEndAndArrivesWithTheAccumulatedResult() throws Exception {
        createTopics("reserve.in", "price.in", "tax.in", completedTopic);
        for (var step : CHECKOUT.steps()) {
            startStep(new StepRunner(step, ACTIONS.get(step), CHECKOUT, publisher, compensations));
        }
        var correlationId = "order-" + UUID.randomUUID();

        try (var completed = consumerFactory.createConsumer("observer-" + correlationId, null)) {
            completed.subscribe(List.of(completedTopic));
            publisher.publish(CHECKOUT.first(), ChoreographyMessage.start(correlationId, CHECKOUT.flowName(), Map.of("sku", "X-1", "quantity", 10)));

            var done = awaitCompletion(completed, correlationId, Duration.ofSeconds(30));

            assertThat(done.resultOf("reserve")).contains(Map.of("quantity", 10));
            assertThat(done.resultOf("price")).contains(Map.of("amount", 30));
            assertThat(done.resultOf("tax")).contains(Map.of("total", 33));

            var trace = TraceGraph.of(done.trace());
            var reserve = entryFor(done, "reserve");
            var price = entryFor(done, "price");
            var tax = entryFor(done, "tax");
            assertThat(reserve.parents()).isEmpty();
            assertThat(price.parents()).containsExactly(reserve.id());
            assertThat(tax.parents()).containsExactly(price.id());
            assertThat(trace.leaves()).containsExactly(tax);
            // The carried trace is all INC-1 needs to undo the instance, newest step first.
            assertThat(CompensationOrdering.of(trace).sequence()).containsExactly(tax, price, reserve);
        }
    }

    private void startStep(StepRunner runner) {
        var container = containers.create(runner);
        container.start();
        running.add(container);
        ContainerTestUtils.waitForAssignment(container, PARTITIONS);
    }

    private static ChoreographyMessage awaitCompletion(
            Consumer<String, ChoreographyMessage> consumer,
            String correlationId,
            Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (var record : consumer.poll(Duration.ofMillis(250))) {
                if (correlationId.equals(record.key())) {
                    return record.value();
                }
            }
        }
        throw new AssertionError("instance " + correlationId + " did not complete within " + timeout);
    }

    private static TraceEntry entryFor(ChoreographyMessage message, String stepId) {
        return message.trace().stream().filter(e -> e.stepId().equals(stepId)).findFirst().orElseThrow();
    }

    private static int number(ChoreographyMessage message, String stepId, String field) {
        return (int) message.resultOf(stepId).orElseThrow().get(field);
    }
}
