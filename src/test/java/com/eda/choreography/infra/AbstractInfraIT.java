package com.eda.choreography.infra;

import static org.awaitility.Awaitility.await;

import com.eda.choreography.domain.resolution.Resolution;
import com.eda.choreography.domain.resolution.ServiceTable;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>Discovery itself is not run. Its {@code service-events} topic is created the way discovery
 * declares it (compacted, three partitions, before anything reads it), and {@link FakeDiscovery}
 * writes the events.
 */
@SpringBootTest
public abstract class AbstractInfraIT {

    protected static final int PARTITIONS = 3;

    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    /** Writes discovery's events; see {@link FakeDiscovery} for what it is and is not. */
    protected static final FakeDiscovery DISCOVERY;

    static {
        KAFKA.start();
        REDIS.start();
        createServiceEventsTopic();
        DISCOVERY = new FakeDiscovery(KAFKA.getBootstrapServers());
    }

    /** The engine's own table of services, fed from {@code service-events}. */
    @Autowired
    protected ServiceTable serviceTable;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Short, so a test of an undo that keeps failing does not wait five minutes.
        registry.add("choreography.compensation.retry-for", () -> "2s");
        // Short, so a test of a service that never appears does not wait half a minute.
        registry.add("choreography.late-binding.wait", () -> "3s");
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

    /**
     * Has discovery register the services and report them healthy, and waits until the engine
     * resolves each one, so a step can be sent to it.
     */
    protected void registerWithDiscovery(String... services) {
        DISCOVERY.registerHealthy(services);
        for (var service : services) {
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> serviceTable.resolve(service).filter(Resolution::live).isPresent());
        }
    }

    private static void createServiceEventsTopic() {
        try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(FakeDiscovery.TOPIC, PARTITIONS, (short) 1)
                    .configs(Map.of("cleanup.policy", "compact")))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("could not create " + FakeDiscovery.TOPIC, e);
        }
    }
}
