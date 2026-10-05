package com.eda.choreography.infra.kafka;

import com.eda.choreography.domain.compensation.CompensationPublisher;
import com.eda.choreography.domain.compensation.CompensationRequest;
import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.Objects;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes compensation to Kafka: a request goes to the step's {@code <step>.compensate}
 * topic, and an instance that has been fully undone goes to one compensated topic. Records are
 * keyed by correlation id, so the requests for one entry reach one consumer, in order.
 */
public class KafkaCompensationPublisher implements CompensationPublisher {

    private final KafkaTemplate<String, CompensationRequest> requests;
    private final KafkaTemplate<String, ChoreographyMessage> messages;
    private final String compensatedTopic;

    public KafkaCompensationPublisher(
            KafkaTemplate<String, CompensationRequest> requests,
            KafkaTemplate<String, ChoreographyMessage> messages,
            String compensatedTopic) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.compensatedTopic = Objects.requireNonNull(compensatedTopic, "compensatedTopic");
    }

    @Override
    public void publish(String stepId, CompensationRequest request) {
        BlockingSend.send(
                requests, StepTopics.compensationTopic(stepId), request.instance().correlationId(), request);
    }

    @Override
    public void publishCompensated(ChoreographyMessage instance) {
        BlockingSend.send(messages, compensatedTopic, instance.correlationId(), instance);
    }
}
