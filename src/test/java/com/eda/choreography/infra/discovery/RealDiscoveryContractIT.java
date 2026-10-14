package com.eda.choreography.infra.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eda.choreography.domain.resolution.Resolution;
import com.eda.choreography.domain.resolution.ServiceTable;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The contract with the real discovery platform, not the fake. The image its own pipeline
 * published is started (pinned by commit), a service registers through discovery's public
 * {@code POST /register} and answers its health probe, and the engine's own listener resolves the
 * service from {@code service-events}, first known and then live.
 *
 * <p>The only test that runs discovery itself; every other one uses {@code FakeDiscovery}. The
 * image can be overridden with {@code -Ddiscovery.image=...}.
 */
class RealDiscoveryContractIT {

    private static final String IMAGE = System.getProperty(
            "discovery.image", "ghcr.io/ermi9/discovery-service:2030f859e7d41f3bbbe3a4983800fe048e78d88e");

    private static final Network NETWORK = Network.newNetwork();
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"))
            .withNetwork(NETWORK)
            .withListener("kafka:19092");
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withNetwork(NETWORK)
            .withNetworkAliases("redis");
    private static final GenericContainer<?> DISCOVERY = new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withNetwork(NETWORK)
            .withAccessToHost(true)
            .withEnv("SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:19092")
            .withEnv("SPRING_DATA_REDIS_HOST", "redis")
            .withEnv("HEALTHCHECK_INTERVALMS", "2000")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    private static HttpServer service;

    @BeforeAll
    static void startDiscoveryAndAService() throws Exception {
        KAFKA.start();
        REDIS.start();
        DISCOVERY.start();
        // A stand-in service: all discovery needs from it is a health endpoint it can probe.
        service = HttpServer.create(new InetSocketAddress(0), 0);
        service.createContext("/health", exchange -> {
            var body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        service.start();
        Testcontainers.exposeHostPorts(service.getAddress().getPort());
    }

    @AfterAll
    static void stopAll() {
        service.stop(0);
        DISCOVERY.stop();
        REDIS.stop();
        KAFKA.stop();
    }

    @Test
    void aServiceRegisteredWithTheRealDiscoveryIsResolvedFromItsStreamAndTurnsLiveOnceProbed() throws Exception {
        var table = new ServiceTable();
        var listener = ServiceEventsListener.create(
                ServiceEventsListener.consumerFactory(KAFKA.getBootstrapServers()), "service-events", "contract-test", table);
        listener.start();
        try {
            var url = "http://host.testcontainers.internal:" + service.getAddress().getPort();
            var registered = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://" + DISCOVERY.getHost() + ":" + DISCOVERY.getMappedPort(8080) + "/register"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"name\":\"fraud-check\",\"url\":\"" + url + "\",\"openapiUrl\":\"" + url + "/openapi\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(registered.statusCode()).isEqualTo(200);

            await().atMost(Duration.ofSeconds(60)).until(() -> table.resolve("fraud-check").isPresent());
            assertThat(table.resolve("fraud-check"))
                    .hasValueSatisfying(resolution -> {
                        assertThat(resolution.inputTopic()).isEqualTo("fraud-check.in");
                        assertThat(resolution.compensationTopic()).isEqualTo("fraud-check.compensate");
                    });
            await().atMost(Duration.ofSeconds(60)).until(() -> table.resolve("fraud-check").map(Resolution::live).orElse(false));
        } finally {
            listener.stop();
        }
    }
}
