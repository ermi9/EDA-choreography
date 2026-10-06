package com.eda.choreography.infra.kafka;

import com.eda.choreography.domain.compensation.CompensationAction;
import com.eda.choreography.domain.compensation.CompensationPublisher;
import com.eda.choreography.domain.compensation.CompensationRequest;
import com.eda.choreography.domain.compensation.CompensationRunner;
import com.eda.choreography.domain.compensation.CompensationTrigger;
import com.eda.choreography.domain.join.JoinDeadlines;
import com.eda.choreography.domain.join.JoinStateMachine;
import com.eda.choreography.domain.join.JoinStateStore;
import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.step.JoinRunner;
import com.eda.choreography.domain.step.MessagePublisher;
import com.eda.choreography.domain.step.NextSteps;
import com.eda.choreography.domain.step.StepAction;
import com.eda.choreography.domain.step.StepRunner;
import com.eda.choreography.infra.AbstractInfraIT;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;

/**
 * Runs flows end to end over the real broker and Redis. Every step, join and compensator gets
 * its own listener container, as it would in its own service.
 *
 * <p>Step names carry a random suffix, so one test never consumes another test's messages.
 */
abstract class AbstractFlowIT extends AbstractInfraIT {

    @Autowired
    StepContainerFactory containers;

    @Autowired
    MessagePublisher publisher;

    @Autowired
    CompensationPublisher compensationPublisher;

    @Autowired
    CompensationTrigger compensations;

    @Autowired
    JoinStateStore joinStore;

    @Autowired
    JoinDeadlines deadlines;

    @Autowired
    ConsumerFactory<String, ChoreographyMessage> consumerFactory;

    @Autowired
    ConsumerFactory<String, CompensationRequest> requestConsumerFactory;

    @Value("${choreography.kafka.completed-topic}")
    String completedTopic;

    @Value("${choreography.kafka.compensated-topic}")
    String compensatedTopic;

    @Value("${choreography.kafka.compensation-failed-topic}")
    String compensationFailedTopic;

    /** The steps undone so far, in the order the compensators undid them. */
    final Queue<String> undone = new ConcurrentLinkedQueue<>();

    private final List<MessageListenerContainer> running = new ArrayList<>();

    @AfterEach
    void stopEverything() {
        running.forEach(MessageListenerContainer::stop);
    }

    static String unique(String name) {
        return name + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static String newInstance() {
        return "order-" + UUID.randomUUID();
    }

    /** Creates the input and compensation topics of the given steps, and the shared outcome topics. */
    void createTopicsFor(String... steps) throws Exception {
        var topics = new ArrayList<String>(List.of(completedTopic, compensatedTopic, compensationFailedTopic));
        for (var step : steps) {
            topics.add(StepTopics.inputTopic(step));
            topics.add(StepTopics.compensationTopic(step));
        }
        createTopics(topics.toArray(String[]::new));
    }

    /** Starts a step together with the compensator that undoes it. */
    void startStep(String stepId, StepAction action, NextSteps routes) {
        startStep(stepId, action, routes, (entry, instance) -> undone.add(entry.stepId()));
    }

    /** Starts a step together with a compensator that undoes it the given way. */
    void startStep(String stepId, StepAction action, NextSteps routes, CompensationAction undo) {
        start(containers.create(new StepRunner(stepId, action, routes, publisher, compensations)));
        startCompensator(stepId, undo);
    }

    /** Starts a join and its compensator, with a fresh state machine over the shared Redis store. */
    ConcurrentMessageListenerContainer<String, ChoreographyMessage> startJoin(
            String stepId, StepAction action, NextSteps routes, int branches, Duration timeout) {
        var container = containers.create(joinRunner(stepId, action, routes, branches, timeout));
        start(container);
        startCompensator(stepId);
        return container;
    }

    JoinRunner joinRunner(String stepId, StepAction action, NextSteps routes, int branches, Duration timeout) {
        return new JoinRunner(
                new StepRunner(stepId, action, routes, publisher, compensations),
                branches, timeout, new JoinStateMachine(joinStore), deadlines, compensations, Clock.systemUTC());
    }

    void startCompensator(String stepId) {
        startCompensator(stepId, (entry, instance) -> undone.add(entry.stepId()));
    }

    void startCompensator(String stepId, CompensationAction undo) {
        start(containers.create(
                new CompensationRunner(stepId, undo, new JoinStateMachine(joinStore), compensationPublisher)));
    }

    void start(MessageListenerContainer container) {
        container.start();
        running.add(container);
        ContainerTestUtils.waitForAssignment(container, PARTITIONS);
    }

    /** A consumer of the completed and compensated topics; create it before starting an instance. */
    Consumer<String, ChoreographyMessage> outcomes() {
        var consumer = consumerFactory.createConsumer("observer-" + UUID.randomUUID(), null);
        consumer.subscribe(List.of(completedTopic, compensatedTopic));
        return consumer;
    }

    /** A consumer of the parked compensation requests. */
    Consumer<String, CompensationRequest> parked() {
        var consumer = requestConsumerFactory.createConsumer("observer-" + UUID.randomUUID(), null);
        consumer.subscribe(List.of(compensationFailedTopic));
        return consumer;
    }

    /** Waits for the first outcome of the instance on the given topic. */
    static ChoreographyMessage await(
            Consumer<String, ChoreographyMessage> outcomes, String topic, String correlationId, Duration timeout) {
        var all = collect(outcomes, topic, List.of(correlationId), 1, timeout);
        if (all.isEmpty()) {
            throw new AssertionError(correlationId + " did not reach " + topic + " within " + timeout);
        }
        return all.get(0);
    }

    /** Collects outcomes of the given instances on one topic until {@code atLeast} arrived or time ran out. */
    static List<ChoreographyMessage> collect(
            Consumer<String, ChoreographyMessage> outcomes,
            String topic,
            List<String> correlationIds,
            int atLeast,
            Duration timeout) {
        var found = new ArrayList<ChoreographyMessage>();
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && found.size() < atLeast) {
            for (var record : outcomes.poll(Duration.ofMillis(250))) {
                if (record.topic().equals(topic) && correlationIds.contains(record.key())) {
                    found.add(record.value());
                }
            }
        }
        return found;
    }

    /** Polls a little longer and returns anything else that arrived for these instances on the topic. */
    static List<ChoreographyMessage> more(
            Consumer<String, ChoreographyMessage> outcomes, String topic, List<String> correlationIds, Duration wait) {
        return collect(outcomes, topic, correlationIds, Integer.MAX_VALUE, wait);
    }
}
