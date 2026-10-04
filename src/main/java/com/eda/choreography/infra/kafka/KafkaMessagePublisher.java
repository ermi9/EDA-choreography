package com.eda.choreography.infra.kafka;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.step.MessagePublisher;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes messages to Kafka: a step's input topic is {@code <step>.in}, and completed
 * instances go to one completed topic.
 *
 * <p>Every record is keyed by correlation id, so all of an instance's messages land on one
 * partition per topic and are consumed in order. INC-4's join relies on that.
 *
 * <p>A send blocks until the broker acknowledges it (bounded by the producer's
 * {@code delivery.timeout.ms}). The listener only acknowledges its input after the step has
 * published, so a failed send means redelivery, never a lost hop.
 */
public class KafkaMessagePublisher implements MessagePublisher {

    private final KafkaTemplate<String, ChoreographyMessage> template;
    private final String completedTopic;

    public KafkaMessagePublisher(KafkaTemplate<String, ChoreographyMessage> template, String completedTopic) {
        this.template = Objects.requireNonNull(template, "template");
        this.completedTopic = Objects.requireNonNull(completedTopic, "completedTopic");
    }

    @Override
    public void publish(String stepId, ChoreographyMessage message) {
        send(StepTopics.inputTopic(stepId), message);
    }

    @Override
    public void publishCompleted(ChoreographyMessage message) {
        send(completedTopic, message);
    }

    private void send(String topic, ChoreographyMessage message) {
        try {
            template.send(topic, message.correlationId(), message).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaException("interrupted while publishing " + message.correlationId() + " to " + topic, e);
        } catch (ExecutionException e) {
            throw new KafkaException("could not publish " + message.correlationId() + " to " + topic, e.getCause());
        }
    }
}
