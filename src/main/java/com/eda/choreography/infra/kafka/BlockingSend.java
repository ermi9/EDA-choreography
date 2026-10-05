package com.eda.choreography.infra.kafka;

import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Sends one record and waits until the broker has acknowledged it (bounded by the producer's
 * {@code delivery.timeout.ms}). Listeners acknowledge their input only after they have
 * published, so a failed send means redelivery, never a lost message.
 */
final class BlockingSend {

    private BlockingSend() {
    }

    static <V> void send(KafkaTemplate<String, V> template, ProducerRecord<String, V> record) {
        try {
            template.send(record).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaException("interrupted while publishing " + record.key() + " to " + record.topic(), e);
        } catch (ExecutionException e) {
            throw new KafkaException("could not publish " + record.key() + " to " + record.topic(), e.getCause());
        }
    }

    static <V> void send(KafkaTemplate<String, V> template, String topic, String key, V value) {
        send(template, new ProducerRecord<>(topic, key, value));
    }
}
