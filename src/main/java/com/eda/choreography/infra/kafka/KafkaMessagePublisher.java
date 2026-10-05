package com.eda.choreography.infra.kafka;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.step.MessagePublisher;
import java.util.Objects;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes messages to Kafka: a step's input topic is {@code <step>.in}, and completed
 * instances go to one completed topic.
 *
 * <p>Every record is keyed by correlation id, so all of an instance's messages land on one
 * partition per topic and are consumed in order. INC-4's join relies on that.
 *
 * <p>A send blocks until the broker acknowledges it (see {@link BlockingSend}).
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
        BlockingSend.send(template, topic, message.correlationId(), message);
    }
}
