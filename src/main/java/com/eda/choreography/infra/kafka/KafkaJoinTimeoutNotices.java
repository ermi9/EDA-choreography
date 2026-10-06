package com.eda.choreography.infra.kafka;

import com.eda.choreography.domain.join.JoinKey;
import com.eda.choreography.domain.join.JoinTimeoutNotices;
import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.Objects;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Sends a join's timeout notice to the join's own input topic, keyed by correlation id like the
 * instance's branches. It therefore lands on the same partition as those branches, and the one
 * consumer of that partition handles notice and branches strictly in turn: Kafka's ordering is
 * the concurrency control, so timing a join out needs no lock.
 *
 * <p>The notice has no value; a header marks it. A null value cannot be mistaken for a message,
 * and the header tells it apart from a record that could not be read.
 */
public class KafkaJoinTimeoutNotices implements JoinTimeoutNotices {

    static final String HEADER = "choreography-join-timeout";

    private final KafkaTemplate<String, ChoreographyMessage> template;

    public KafkaJoinTimeoutNotices(KafkaTemplate<String, ChoreographyMessage> template) {
        this.template = Objects.requireNonNull(template, "template");
    }

    @Override
    public void publish(JoinKey key) {
        var record = new ProducerRecord<String, ChoreographyMessage>(
                StepTopics.inputTopic(key.joinId()), key.correlationId(), null);
        record.headers().add(HEADER, new byte[0]);
        BlockingSend.send(template, record);
    }

    static boolean isTimeoutNotice(Headers headers) {
        return headers.lastHeader(HEADER) != null;
    }
}
