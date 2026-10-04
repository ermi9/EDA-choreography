package com.eda.choreography.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;

class KafkaMessagePublisherTest {

    private static final String COMPLETED_TOPIC = "choreography.completed";

    private final ChoreographyMessage message = ChoreographyMessage.start("order-42", "checkout", Map.of()).recordStep("A", Map.of("quantity", 10));

    @Test
    void sendsToTheNextStepsInputTopicKeyedByCorrelationId() {
        var producer = autoCompletingProducer();

        publisherOn(producer).publish("B", message);

        assertThat(producer.history()).singleElement().satisfies(record -> {
            assertThat(record.topic()).isEqualTo("B.in");
            // One key per instance keeps an instance on one partition, in order.
            assertThat(record.key()).isEqualTo("order-42");
            assertThat(record.value()).isEqualTo(message);
        });
    }

    @Test
    void sendsACompletedInstanceToTheCompletedTopic() {
        var producer = autoCompletingProducer();

        publisherOn(producer).publishCompleted(message);

        assertThat(producer.history()).singleElement().satisfies(record -> {
            assertThat(record.topic()).isEqualTo(COMPLETED_TOPIC);
            assertThat(record.key()).isEqualTo("order-42");
        });
    }

    @Test
    void returnsOnlyOnceTheBrokerHasAcceptedAndReportsAFailedSend() {
        var producer = new MockProducer<>(false, null, new StringSerializer(), MessageWireFormat.serializer());
        failTheNextSendWithin5Seconds(producer);

        assertThatThrownBy(() -> publisherOn(producer).publish("B", message))
                .isInstanceOf(KafkaException.class)
                .hasMessageContaining("B.in")
                .hasRootCauseMessage("broker unreachable");
    }

    /** The send is in flight while publish() blocks on it, so the failure has to come from elsewhere. */
    private static void failTheNextSendWithin5Seconds(MockProducer<String, ChoreographyMessage> producer) {
        var deadline = System.nanoTime() + 5_000_000_000L;
        CompletableFuture.runAsync(() -> {
            while (!producer.errorNext(new TimeoutException("broker unreachable")) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        });
    }

    private static MockProducer<String, ChoreographyMessage> autoCompletingProducer() {
        return new MockProducer<>(true, null, new StringSerializer(), MessageWireFormat.serializer());
    }

    private static KafkaMessagePublisher publisherOn(MockProducer<String, ChoreographyMessage> producer) {
        return new KafkaMessagePublisher(new KafkaTemplate<>(new MockProducerFactory<>(() -> producer)), COMPLETED_TOPIC);
    }
}
