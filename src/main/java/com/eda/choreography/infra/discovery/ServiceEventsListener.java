package com.eda.choreography.infra.discovery;

import com.eda.choreography.domain.resolution.ServiceEvent;
import com.eda.choreography.domain.resolution.ServiceTable;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;

/**
 * Replays discovery's {@code service-events} topic into a {@link ServiceTable}, the way
 * discovery's contract prescribes for a consumer:
 * <ul>
 *   <li>its own consumer group, starting at offset 0, so the whole compacted log is replayed;</li>
 *   <li>its own DTO, read by schema with no type header, and unknown fields ignored;</li>
 *   <li>an {@link ErrorHandlingDeserializer}, so one unreadable record cannot stall it.</li>
 * </ul>
 *
 * <p>Every engine needs every service, so each listener gets a group of its own. A shared group
 * would split the topic's partitions between engines, and each would know only part of the
 * services. The group's offsets are of no use to anyone after it stops; Kafka expires them.
 */
public final class ServiceEventsListener {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceEventsListener.class);

    private ServiceEventsListener() {
    }

    /** Reads an event by its JSON fields alone; a record that cannot be read comes out as {@code null}. */
    static Deserializer<ServiceEvent> deserializer() {
        return new ErrorHandlingDeserializer<>(new JacksonJsonDeserializer<>(ServiceEvent.class).ignoreTypeHeaders());
    }

    public static ConsumerFactory<String, ServiceEvent> consumerFactory(String bootstrapServers) {
        return new DefaultKafkaConsumerFactory<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),
                new StringDeserializer(),
                deserializer());
    }

    /**
     * A container that applies every event on the topic to the table, from the first one on. The
     * caller starts and stops it.
     *
     * @param groupPrefix a readable prefix; a random suffix makes the group this listener's own
     */
    public static ConcurrentMessageListenerContainer<String, ServiceEvent> create(
            ConsumerFactory<String, ServiceEvent> consumers, String topic, String groupPrefix, ServiceTable table) {
        var properties = new ContainerProperties(topic);
        properties.setGroupId(groupPrefix + "-" + UUID.randomUUID());
        properties.setMessageListener((MessageListener<String, ServiceEvent>) record -> {
            if (record.value() == null) {
                LOG.warn("skipping an unreadable service event for {} at {}-{}@{}",
                        record.key(), record.topic(), record.partition(), record.offset());
                return;
            }
            table.apply(record.value());
        });
        var container = new ConcurrentMessageListenerContainer<>(consumers, properties);
        container.setBeanName("service-events");
        return container;
    }
}
