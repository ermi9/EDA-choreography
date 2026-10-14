package com.eda.choreography.infra.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.eda.choreography.domain.resolution.Resolution;
import com.eda.choreography.domain.resolution.ServiceTable;
import com.eda.choreography.infra.AbstractInfraIT;
import com.eda.choreography.infra.FakeDiscovery;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** The engine's service table, fed by contract-shaped events on the real broker (see {@code FakeDiscovery}). */
class ServiceEventsIT extends AbstractInfraIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    ServiceTable table;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Test
    void theEngineResolvesAServiceOnceDiscoveryHasRegisteredItAndReportedItHealthy() {
        var payment = unique("payment");

        DISCOVERY.registerHealthy(payment);

        await().atMost(WAIT).until(() -> table.resolve(payment).filter(Resolution::live).isPresent());
        assertThat(table.resolve(payment)).contains(new Resolution(payment + ".in", payment + ".compensate", true));
    }

    @Test
    void aStaleStatusChangeOnTheStreamDoesNotTakeAHealthyServiceDown() {
        var payment = unique("payment");

        DISCOVERY.send("SERVICE_REGISTERED", payment, "unknown", 5);
        DISCOVERY.send("STATUS_CHANGED", payment, "healthy", 7);
        DISCOVERY.send("STATUS_CHANGED", payment, "unavailable", 6);
        DISCOVERY.awaitCaughtUp(table, payment);

        assertThat(table.resolve(payment).map(Resolution::live)).contains(true);
    }

    @Test
    void aListenerStartedLaterRebuildsTheSameTableFromTheStartOfTheStream() {
        var payment = unique("payment");
        DISCOVERY.registerHealthy(payment);
        DISCOVERY.awaitCaughtUp(table, payment);

        var rebuilt = new ServiceTable();
        var listener = ServiceEventsListener.create(
                ServiceEventsListener.consumerFactory(bootstrapServers), FakeDiscovery.TOPIC, "replay-test", rebuilt);
        listener.start();
        try {
            await().atMost(WAIT).until(() -> rebuilt.resolve(payment).equals(table.resolve(payment)));
        } finally {
            listener.stop();
        }
    }

    @Test
    void aRecordThatIsNotAnEventIsSkippedAndTheEventsAfterItAreRead() {
        var payment = unique("payment");

        DISCOVERY.sendRaw(payment, "{not an event");
        DISCOVERY.registerHealthy(payment);

        await().atMost(WAIT).until(() -> table.resolve(payment).filter(Resolution::live).isPresent());
    }

    @Test
    void aDeregisteredServiceIsNoLongerLive() {
        var payment = unique("payment");
        DISCOVERY.registerHealthy(payment);
        await().atMost(WAIT).until(() -> table.resolve(payment).filter(Resolution::live).isPresent());

        DISCOVERY.send("SERVICE_DEREGISTERED", payment, "unavailable", 2);

        await().atMost(WAIT).until(() -> table.resolve(payment).map(Resolution::live).equals(Optional.of(false)));
    }

    private static String unique(String name) {
        return name + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
