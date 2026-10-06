package com.eda.choreography.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.join.JoinKey;
import com.eda.choreography.domain.message.ChoreographyMessage;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;

class KafkaJoinTimeoutNoticesTest {

    @Test
    void sendsAnEmptyMarkedRecordToTheJoinKeyedLikeTheInstancesBranches() {
        var producer = new MockProducer<>(true, null, new StringSerializer(), MessageWireFormat.<ChoreographyMessage>serializer());
        var notices = new KafkaJoinTimeoutNotices(new KafkaTemplate<>(new MockProducerFactory<>(() -> producer)));

        notices.publish(new JoinKey("order-42", "J"));

        assertThat(producer.history()).singleElement().satisfies(record -> {
            assertThat(record.topic()).isEqualTo("J.in");
            assertThat(record.key()).isEqualTo("order-42");
            assertThat(record.value()).isNull();
            assertThat(KafkaJoinTimeoutNotices.isTimeoutNotice(record.headers())).isTrue();
        });
    }

    @Test
    void anOrdinaryRecordIsNoNotice() {
        assertThat(KafkaJoinTimeoutNotices.isTimeoutNotice(new RecordHeaders())).isFalse();
    }
}
