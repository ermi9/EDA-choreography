package com.eda.choreography.infra.kafka;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.step.StepRunner;
import java.util.Objects;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Builds the Kafka listener that feeds one step: it consumes {@code <step>.in} and hands each
 * message to the step's {@link StepRunner}. The caller starts and stops the container.
 *
 * <p>The consumer group is the step id, so every running copy of a service shares that step's
 * work and each message is handled by one of them.
 *
 * <p>Offsets are committed per record, after {@link StepRunner#handle} returns, which is after
 * the outgoing message was acknowledged by the broker: at-least-once, never a lost hop. A step
 * that throws is retried twice, a second apart, and then logged and skipped. Turning an
 * exhausted step into a failed trace entry and compensating is later work (INC-4 onwards).
 * A record that cannot be deserialized is skipped at once (see {@link MessageWireFormat}).
 */
public class StepContainerFactory {

    private static final FixedBackOff RETRY_TWICE_A_SECOND_APART = new FixedBackOff(1_000L, 2L);

    private final ConsumerFactory<String, ChoreographyMessage> consumerFactory;

    public StepContainerFactory(ConsumerFactory<String, ChoreographyMessage> consumerFactory) {
        this.consumerFactory = Objects.requireNonNull(consumerFactory, "consumerFactory");
    }

    public ConcurrentMessageListenerContainer<String, ChoreographyMessage> create(StepRunner runner) {
        var properties = new ContainerProperties(StepTopics.inputTopic(runner.stepId()));
        properties.setGroupId(runner.stepId());
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setMessageListener(
                (MessageListener<String, ChoreographyMessage>) record -> runner.handle(record.value()));

        var container = new ConcurrentMessageListenerContainer<>(consumerFactory, properties);
        container.setBeanName("step-" + runner.stepId());
        container.setCommonErrorHandler(new DefaultErrorHandler(RETRY_TWICE_A_SECOND_APART));
        return container;
    }
}
