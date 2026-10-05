package com.eda.choreography.infra;

import java.util.Arrays;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A Spring context against one real Kafka broker, started once per JVM and shared by every
 * integration test (Ryuk removes it when the JVM exits).
 *
 * <p>The image is {@code apache/kafka:3.7.0}, the broker the discovery platform deploys, rather
 * than Testcontainers' default Confluent image.
 */
@SpringBootTest
public abstract class AbstractInfraIT {

    protected static final int PARTITIONS = 3;

    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /**
     * Topics are created up front, as discovery provisions them for registered services. A step
     * container can then be assigned its partitions before the first message is sent.
     */
    protected static void createTopics(String... topics) throws Exception {
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            var newTopics = Arrays.stream(topics).map(topic -> new NewTopic(topic, PARTITIONS, (short) 1)).toList();
            admin.createTopics(newTopics).all().get();
        }
    }
}
