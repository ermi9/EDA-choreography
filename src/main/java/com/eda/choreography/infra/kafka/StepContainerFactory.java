package com.eda.choreography.infra.kafka;

import com.eda.choreography.domain.compensation.CompensationRequest;
import com.eda.choreography.domain.compensation.CompensationRunner;
import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.step.JoinRunner;
import com.eda.choreography.domain.step.StepRunner;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Builds the Kafka listeners that feed one service: a step's or join's {@code <step>.in}, and
 * its {@code <step>.compensate}. The caller starts and stops the containers.
 *
 * <p>The consumer group is the step id (plus {@code -compensate} for compensation), so every
 * running copy of a service shares that step's work and each message is handled by one of them.
 *
 * <p>Offsets are committed per record, after the runner returns, which is after anything it
 * published was acknowledged by the broker: at-least-once, never a lost hop.
 *
 * <p>A step that throws is retried twice, a second apart, and then fails: the runner records
 * the failure and the instance is undone or the failure goes to the join. If even that cannot
 * be published, the record is delivered again.
 *
 * <p>A compensation that throws is retried for longer, with a growing pause (1 s doubling up to
 * 30 s), because most undo failures are passing outages. Once the configured time is spent the
 * request is parked for an operator (see {@link CompensationRunner#giveUp}) instead of blocking
 * the partition for every other instance. A record that cannot be read is logged and skipped at
 * once (see {@link MessageWireFormat}).
 */
public class StepContainerFactory {

    private static final Logger LOG = LoggerFactory.getLogger(StepContainerFactory.class);

    private static final FixedBackOff RETRY_TWICE_A_SECOND_APART = new FixedBackOff(1_000L, 2L);
    private final ConsumerFactory<String, ChoreographyMessage> messages;
    private final ConsumerFactory<String, CompensationRequest> requests;
    private final Duration compensationRetryFor;

    /**
     * @param compensationRetryFor how long a failing compensation is retried before it is parked
     */
    public StepContainerFactory(
            ConsumerFactory<String, ChoreographyMessage> messages,
            ConsumerFactory<String, CompensationRequest> requests,
            Duration compensationRetryFor) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.compensationRetryFor = Objects.requireNonNull(compensationRetryFor, "compensationRetryFor");
    }

    public ConcurrentMessageListenerContainer<String, ChoreographyMessage> create(StepRunner runner) {
        return messageContainer(
                runner.stepId(),
                record -> runner.handle(record.value()),
                record -> runner.fail(record.value()));
    }

    /**
     * A join's topic carries its branches and its timeout notices, both keyed by correlation id,
     * so one consumer takes an instance's branches and its timeout strictly in turn.
     */
    public ConcurrentMessageListenerContainer<String, ChoreographyMessage> create(JoinRunner runner) {
        return messageContainer(runner.stepId(), forJoin(runner, runner::handle), forJoin(runner, runner::fail));
    }

    private static Consumer<ConsumerRecord<String, ChoreographyMessage>> forJoin(
            JoinRunner runner, Consumer<ChoreographyMessage> branch) {
        return record -> {
            if (KafkaJoinTimeoutNotices.isTimeoutNotice(record.headers())) {
                runner.timeOut(record.key());
            } else {
                branch.accept(record.value());
            }
        };
    }

    public ConcurrentMessageListenerContainer<String, CompensationRequest> create(CompensationRunner runner) {
        var properties = new ContainerProperties(StepTopics.compensationTopic(runner.stepId()));
        properties.setGroupId(runner.stepId() + "-compensate");
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setMessageListener(
                (MessageListener<String, CompensationRequest>) record -> runner.handle(record.value()));

        var container = new ConcurrentMessageListenerContainer<>(requests, properties);
        container.setBeanName("compensate-" + runner.stepId());
        container.setCommonErrorHandler(new DefaultErrorHandler(
                (record, exception) -> {
                    var request = (CompensationRequest) record.value();
                    if (request == null) {
                        LOG.error("compensator {} skips an unreadable record of {} at {}-{}@{}", runner.stepId(),
                                record.key(), record.topic(), record.partition(), record.offset(), exception);
                        return;
                    }
                    LOG.error("compensator {} parks entry {} of {} after retrying for {}", runner.stepId(),
                            request.entryId(), record.key(), compensationRetryFor, exception);
                    runner.giveUp(request);
                },
                growingPauseFor(compensationRetryFor)));
        return container;
    }

    private static ExponentialBackOff growingPauseFor(Duration total) {
        var backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(total.toMillis());
        return backOff;
    }

    private ConcurrentMessageListenerContainer<String, ChoreographyMessage> messageContainer(
            String stepId,
            Consumer<ConsumerRecord<String, ChoreographyMessage>> handle,
            Consumer<ConsumerRecord<String, ChoreographyMessage>> giveUp) {
        var properties = new ContainerProperties(StepTopics.inputTopic(stepId));
        properties.setGroupId(stepId);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setMessageListener((MessageListener<String, ChoreographyMessage>) handle::accept);

        var container = new ConcurrentMessageListenerContainer<>(messages, properties);
        container.setBeanName("step-" + stepId);
        container.setCommonErrorHandler(new DefaultErrorHandler(
                (record, exception) -> giveUpOn(stepId, cast(record), exception, giveUp),
                RETRY_TWICE_A_SECOND_APART));
        return container;
    }

    /**
     * Called once the retries are spent, or at once for a record that cannot be read. Such a
     * record has no message to fail with, so it is only logged. If giving up throws, the error
     * handler delivers the record again.
     */
    private static void giveUpOn(
            String stepId,
            ConsumerRecord<String, ChoreographyMessage> record,
            Exception exception,
            Consumer<ConsumerRecord<String, ChoreographyMessage>> giveUp) {
        if (record.value() == null && !KafkaJoinTimeoutNotices.isTimeoutNotice(record.headers())) {
            LOG.error("step {} skips an unreadable record of {} at {}-{}@{}", stepId, record.key(),
                    record.topic(), record.partition(), record.offset(), exception);
            return;
        }
        LOG.warn("step {} gives up on {} after retries", stepId, record.key(), exception);
        giveUp.accept(record);
    }

    @SuppressWarnings("unchecked")
    private static ConsumerRecord<String, ChoreographyMessage> cast(ConsumerRecord<?, ?> record) {
        return (ConsumerRecord<String, ChoreographyMessage>) record;
    }
}
