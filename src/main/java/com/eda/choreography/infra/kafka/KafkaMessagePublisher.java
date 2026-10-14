package com.eda.choreography.infra.kafka;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.resolution.ResolutionSource;
import com.eda.choreography.domain.step.MessagePublisher;
import java.util.Objects;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes messages to Kafka: a step goes to the input topic discovery gave its service, and
 * completed instances go to one completed topic. A service that is not live is sent nothing
 * (see {@link Destinations}); the send fails and the caller's retries take over.
 *
 * <p>Every record is keyed by correlation id, so all of an instance's messages land on one
 * partition per topic and are consumed in order. INC-4's join relies on that.
 *
 * <p>A send blocks until the broker acknowledges it (see {@link BlockingSend}).
 */
public class KafkaMessagePublisher implements MessagePublisher {

    private final KafkaTemplate<String, ChoreographyMessage> template;
    private final String completedTopic;
    private final Destinations destinations;

    public KafkaMessagePublisher(
            KafkaTemplate<String, ChoreographyMessage> template, String completedTopic, ResolutionSource resolutions) {
        this.template = Objects.requireNonNull(template, "template");
        this.completedTopic = Objects.requireNonNull(completedTopic, "completedTopic");
        this.destinations = new Destinations(resolutions);
    }

    @Override
    public void publish(String stepId, ChoreographyMessage message) {
        send(destinations.workFor(stepId), message);
    }

    @Override
    public void publishCompleted(ChoreographyMessage message) {
        send(completedTopic, message);
    }

    private void send(String topic, ChoreographyMessage message) {
        BlockingSend.send(template, topic, message.correlationId(), message);
    }
}
