package com.eda.choreography.infra;

import static org.awaitility.Awaitility.await;

import com.eda.choreography.domain.resolution.ResolutionSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * STUB, test only: stands in for the discovery platform by writing contract-shaped events to
 * {@code service-events}. It is not discovery and implements none of its behaviour (no
 * registration API, no health checks, no fencing of its own); it only writes the JSON that
 * discovery's {@code docs/ARCHITECTURE.md} documents, keyed by service name, with no type
 * header, including the fields the engine ignores. The test decides every field, generations
 * included.
 */
public final class FakeDiscovery implements AutoCloseable {

    public static final String TOPIC = "service-events";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final KafkaProducer<String, String> producer;
    private final Map<String, Integer> lastPartition = new ConcurrentHashMap<>();

    FakeDiscovery(String bootstrapServers) {
        this.producer = new KafkaProducer<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers),
                new StringSerializer(),
                new StringSerializer());
    }

    /** Registers each service and reports it healthy, the way discovery does for a service that comes up. */
    public void registerHealthy(String... serviceNames) {
        for (var name : serviceNames) {
            send("SERVICE_REGISTERED", name, "unknown", 1);
            send("STATUS_CHANGED", name, "healthy", 2);
        }
    }

    /** Sends one event with the topics discovery derives by convention: {@code <name>.in} and {@code <name>.compensate}. */
    public void send(String type, String serviceName, String status, long generation) {
        send(type, serviceName, status, generation, serviceName + ".in", serviceName + ".compensate");
    }

    public void send(
            String type, String serviceName, String status, long generation, String inputTopic, String compensationTopic) {
        var event = new LinkedHashMap<String, Object>();
        event.put("type", type);
        event.put("serviceName", serviceName);
        event.put("url", "http://" + serviceName + ":8080");
        event.put("openapiUrl", "http://" + serviceName + ":8080/v3/api-docs");
        event.put("status", status);
        event.put("timestamp", LocalDateTime.now().toString());
        event.put("generation", generation);
        event.put("inputTopic", inputTopic);
        event.put("compensationTopic", compensationTopic);
        sendRaw(serviceName, JSON.writeValueAsString(event));
    }

    /** Sends any value under a service's key, such as a record that is not an event at all. */
    public void sendRaw(String serviceName, String value) {
        try {
            var sent = producer.send(new ProducerRecord<>(TOPIC, serviceName, value)).get();
            lastPartition.put(serviceName, sent.partition());
        } catch (Exception e) {
            throw new IllegalStateException("could not send a service event for " + serviceName, e);
        }
    }

    /**
     * Waits until {@code table} has read everything sent so far about a service. A marker service
     * is registered on the same partition, behind those events; once the table knows the marker,
     * it has read past them, because a partition is read in order.
     */
    public void awaitCaughtUp(ResolutionSource table, String serviceName) {
        var marker = "marker-" + UUID.randomUUID();
        var event = Map.of(
                "type", "SERVICE_REGISTERED", "serviceName", marker, "status", "unknown", "generation", 1,
                "inputTopic", marker + ".in", "compensationTopic", marker + ".compensate");
        try {
            producer.send(new ProducerRecord<>(
                    TOPIC, lastPartition.get(serviceName), marker, JSON.writeValueAsString(event))).get();
        } catch (Exception e) {
            throw new IllegalStateException("could not send a marker after " + serviceName, e);
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> table.resolve(marker).isPresent());
    }

    @Override
    public void close() {
        producer.close();
    }
}
