package com.eda.choreography.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.compensation.CompensationRequest;
import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;

class KafkaCompensationPublisherTest {

    private final ChoreographyMessage failed = ChoreographyMessage.start("order-42", "checkout", Map.of())
            .recordStep("A", Map.of("quantity", 10))
            .recordFailure("B");

    private final MockProducer<String, CompensationRequest> requests =
            new MockProducer<>(true, null, new StringSerializer(), MessageWireFormat.<CompensationRequest>serializer());
    private final MockProducer<String, ChoreographyMessage> messages =
            new MockProducer<>(true, null, new StringSerializer(), MessageWireFormat.<ChoreographyMessage>serializer());

    private final KafkaCompensationPublisher publisher = new KafkaCompensationPublisher(
            new KafkaTemplate<>(new MockProducerFactory<>(() -> requests)),
            new KafkaTemplate<>(new MockProducerFactory<>(() -> messages)),
            "choreography.compensated");

    @Test
    void sendsARequestToTheStepsCompensationTopicKeyedByCorrelationId() {
        var request = new CompensationRequest("run-1", failed.trace().get(0).id(), "run-1", failed);

        publisher.publish("A", request);

        assertThat(requests.history()).singleElement().satisfies(record -> {
            assertThat(record.topic()).isEqualTo("A.compensate");
            assertThat(record.key()).isEqualTo("order-42");
            assertThat(record.value()).isEqualTo(request);
        });
    }

    @Test
    void sendsAnUndoneInstanceToTheCompensatedTopic() {
        publisher.publishCompensated(failed);

        assertThat(messages.history()).singleElement().satisfies(record -> {
            assertThat(record.topic()).isEqualTo("choreography.compensated");
            assertThat(record.key()).isEqualTo("order-42");
        });
    }
}
