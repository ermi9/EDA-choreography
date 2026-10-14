package com.eda.choreography.infra.kafka;

import com.eda.choreography.domain.compensation.CompensationPublisher;
import com.eda.choreography.domain.compensation.CompensationRequest;
import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.resolution.ResolutionSource;
import java.util.Objects;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes compensation to Kafka: a request goes to the compensation topic discovery gave the
 * step's service (live or not, see {@link Destinations}), an instance that has been fully undone goes to one compensated topic, and a request
 * that could not be carried out is parked on one failed topic. Records are keyed by correlation
 * id, so the requests for one entry reach one consumer, in order.
 */
public class KafkaCompensationPublisher implements CompensationPublisher {

    private final KafkaTemplate<String, CompensationRequest> requests;
    private final KafkaTemplate<String, ChoreographyMessage> messages;
    private final String compensatedTopic;
    private final String failedTopic;
    private final Destinations destinations;

    public KafkaCompensationPublisher(
            KafkaTemplate<String, CompensationRequest> requests,
            KafkaTemplate<String, ChoreographyMessage> messages,
            String compensatedTopic,
            String failedTopic,
            ResolutionSource resolutions) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.compensatedTopic = Objects.requireNonNull(compensatedTopic, "compensatedTopic");
        this.failedTopic = Objects.requireNonNull(failedTopic, "failedTopic");
        this.destinations = new Destinations(resolutions);
    }

    @Override
    public void publish(String stepId, CompensationRequest request) {
        BlockingSend.send(
                requests, destinations.undoFor(stepId), request.instance().correlationId(), request);
    }

    @Override
    public void publishCompensated(ChoreographyMessage instance) {
        BlockingSend.send(messages, compensatedTopic, instance.correlationId(), instance);
    }

    @Override
    public void publishFailed(CompensationRequest request) {
        BlockingSend.send(requests, failedTopic, request.instance().correlationId(), request);
    }
}
