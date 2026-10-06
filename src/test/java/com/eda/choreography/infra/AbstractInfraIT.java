package com.eda.choreography.infra;

import java.util.Arrays;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A Spring context against one real Kafka broker and one real Redis, each started once per JVM
 * and shared by every integration test (Ryuk removes them when the JVM exits).
 *
 * <p>The images are the ones the discovery platform runs: {@code apache/kafka:3.7.0} rather
 * than Testcontainers' default Confluent image, and {@code redis:7-alpine}.
 */
@SpringBootTest
public abstract class AbstractInfraIT {

    protected static final int PARTITIONS = 3;

    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        KAFKA.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Short, so a test of an undo that keeps failing does not wait five minutes.
        registry.add("choreography.compensation.retry-for", () -> "2s");
    }

    /**
     * Topics are created up front, as discovery provisions them for registered services. A step
     * container can then be assigned its partitions before the first message is sent. Topics
     * that already exist, such as the shared completed topic, are left as they are.
     */
    protected static void createTopics(String... topics) throws Exception {
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            var existing = admin.listTopics().names().get();
            var newTopics = Arrays.stream(topics)
                    .distinct()
                    .filter(topic -> !existing.contains(topic))
                    .map(topic -> new NewTopic(topic, PARTITIONS, (short) 1))
                    .toList();
            admin.createTopics(newTopics).all().get();
        }
    }
}
