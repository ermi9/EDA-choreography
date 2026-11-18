package com.eda.choreography.infra.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.resolution.ServiceEvent;
import com.eda.choreography.domain.resolution.ServiceTable;
import com.eda.choreography.infra.web.ServiceSpecs.Spec;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServiceSpecsTest {

    private static final String SPEC = "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"payment\",\"version\":\"1\"}}";

    private final ServiceTable table = new ServiceTable();
    private final AtomicInteger hits = new AtomicInteger();
    private final MutableClock clock = new MutableClock();
    private HttpServer server;
    private ServiceSpecs specs;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/spec", exchange -> {
            hits.incrementAndGet();
            var body = SPEC.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/huge", exchange -> {
            var body = new byte[2_048];
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        specs = new ServiceSpecs(table, Duration.ofMillis(500), 1_024, Duration.ofSeconds(30), clock);
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void fetchesTheSpecFromTheAddressTheServiceRegistered() {
        register("payment", stub("/spec"));

        assertThat(specs.has("payment")).isTrue();
        assertThat(specs.fetch("payment")).isEqualTo(new Spec.Found(SPEC, "application/json"));
    }

    @Test
    void aServiceThatRegisteredNoSpecHasNone() {
        register("payment", null);

        assertThat(specs.has("payment")).isFalse();
        assertThat(specs.fetch("payment")).isEqualTo(new Spec.None());
        assertThat(specs.fetch("unheard-of")).isEqualTo(new Spec.None());
    }

    @Test
    void aServiceThatAnswersWithAnErrorIsUnreachable() {
        register("payment", stub("/broken"));

        assertThat(specs.fetch("payment")).isInstanceOfSatisfying(Spec.Unreachable.class,
                unreachable -> assertThat(unreachable.reason()).contains("500"));
    }

    @Test
    void aServiceThatAnswersTooSlowlyIsUnreachable() {
        register("payment", stub("/slow"));

        assertThat(specs.fetch("payment")).isInstanceOf(Spec.Unreachable.class);
    }

    @Test
    void aSpecLargerThanTheCapIsRefused() {
        register("payment", stub("/huge"));

        assertThat(specs.fetch("payment")).isInstanceOfSatisfying(Spec.Unreachable.class,
                unreachable -> assertThat(unreachable.reason()).contains("larger than 1024"));
    }

    @Test
    void anAddressThatIsNotHttpIsNeverFetched() {
        register("payment", "file:///etc/passwd");

        assertThat(specs.fetch("payment")).isInstanceOfSatisfying(Spec.Unreachable.class,
                unreachable -> assertThat(unreachable.reason()).contains("only http and https"));
    }

    @Test
    void aFoundSpecIsKeptForAWhileThenFetchedAgain() {
        register("payment", stub("/spec"));

        specs.fetch("payment");
        specs.fetch("payment");
        assertThat(hits).hasValue(1);

        clock.advance(Duration.ofSeconds(31));
        specs.fetch("payment");
        assertThat(hits).hasValue(2);
    }

    @Test
    void aServiceThatRegistersAgainWithAnotherAddressIsFetchedAfresh() {
        register("payment", stub("/spec"));
        specs.fetch("payment");

        register("payment", stub("/broken"));

        assertThat(specs.fetch("payment")).isInstanceOf(Spec.Unreachable.class);
    }

    private void register(String service, String openapiUrl) {
        table.apply(new ServiceEvent(ServiceEvent.REGISTERED, service, "healthy", 1,
                service + ".in", service + ".compensate", "http://" + service, openapiUrl));
    }

    private String stub(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-18T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
