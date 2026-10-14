package com.eda.choreography.domain.resolution;

import static com.eda.choreography.domain.resolution.ServiceEvent.DEREGISTERED;
import static com.eda.choreography.domain.resolution.ServiceEvent.REGISTERED;
import static com.eda.choreography.domain.resolution.ServiceEvent.STATUS_CHANGED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServiceTableTest {

    private final ServiceTable table = new ServiceTable();

    @Test
    void aServiceDiscoveryNeverMentionedDoesNotResolve() {
        assertThat(table.resolve("payment")).isEmpty();
    }

    @Test
    void aRegisteredServiceResolvesToTheTopicsDiscoveryGaveItButIsNotLiveBeforeItIsHealthy() {
        table.apply(event(REGISTERED, "payment", "unknown", 1));

        assertThat(table.resolve("payment")).contains(new Resolution("payment.in", "payment.compensate", false));
    }

    @Test
    void onlyAHealthyServiceIsLive() {
        table.apply(event(REGISTERED, "payment", "unknown", 1));

        table.apply(event(STATUS_CHANGED, "payment", "healthy", 2));
        assertThat(table.resolve("payment")).hasValueSatisfying(resolution -> assertThat(resolution.live()).isTrue());

        for (var status : new String[] {"not-ready", "unavailable", "unknown", "HEALTHY", "degraded"}) {
            table.apply(event(STATUS_CHANGED, "payment", status, 3));
            assertThat(table.resolve("payment"))
                    .as(status)
                    .hasValueSatisfying(resolution -> assertThat(resolution.live()).isFalse());
        }
    }

    @Test
    void aStatusChangeOfAServiceWhoseRegistrationWasCompactedAwayStillResolves() {
        // A compacted topic keeps only the latest event per service, so a replay may start there.
        table.apply(event(STATUS_CHANGED, "payment", "healthy", 4));

        assertThat(table.resolve("payment")).contains(new Resolution("payment.in", "payment.compensate", true));
    }

    @Test
    void theTopicsAreTheOnesOnTheEventNotOnesDerivedFromTheName() {
        table.apply(new ServiceEvent(STATUS_CHANGED, "payment", "healthy", 1, "billing-v2.in", "billing-v2.undo"));

        assertThat(table.resolve("payment")).contains(new Resolution("billing-v2.in", "billing-v2.undo", true));
    }

    @Test
    void aStatusChangeBelowTheHighestGenerationSeenIsStaleAndDropped() {
        table.apply(event(REGISTERED, "payment", "unknown", 5));
        table.apply(event(STATUS_CHANGED, "payment", "healthy", 7));

        // A deposed partition leader's late report must not take a healthy service down.
        table.apply(event(STATUS_CHANGED, "payment", "unavailable", 6));
        assertThat(table.resolve("payment")).hasValueSatisfying(resolution -> assertThat(resolution.live()).isTrue());

        table.apply(event(STATUS_CHANGED, "payment", "unavailable", 7));
        assertThat(table.resolve("payment")).hasValueSatisfying(resolution -> assertThat(resolution.live()).isFalse());
    }

    @Test
    void aRegistrationIsANewLifecycleThatResetsTheFence() {
        table.apply(event(STATUS_CHANGED, "payment", "healthy", 9));

        table.apply(event(REGISTERED, "payment", "unknown", 2));
        table.apply(event(STATUS_CHANGED, "payment", "healthy", 3));

        assertThat(table.resolve("payment")).hasValueSatisfying(resolution -> assertThat(resolution.live()).isTrue());
    }

    @Test
    void aDeregisteredServiceIsNotLiveButStillResolvesSoItsStepsCanBeUndone() {
        table.apply(event(STATUS_CHANGED, "payment", "healthy", 3));

        table.apply(event(DEREGISTERED, "payment", "unavailable", 3));

        assertThat(table.resolve("payment")).contains(new Resolution("payment.in", "payment.compensate", false));
    }

    @Test
    void onlyANewRegistrationBringsADeregisteredServiceBack() {
        table.apply(event(STATUS_CHANGED, "payment", "healthy", 3));
        table.apply(event(DEREGISTERED, "payment", "unavailable", 3));

        // Discovery deleted the service, so a status change after that is from before.
        table.apply(event(STATUS_CHANGED, "payment", "healthy", 4));
        assertThat(table.resolve("payment")).hasValueSatisfying(resolution -> assertThat(resolution.live()).isFalse());

        table.apply(event(REGISTERED, "payment", "unknown", 1));
        table.apply(event(STATUS_CHANGED, "payment", "healthy", 2));
        assertThat(table.resolve("payment")).hasValueSatisfying(resolution -> assertThat(resolution.live()).isTrue());
    }

    @Test
    void ignoresEventsItCannotUse() {
        table.apply(event("SERVICE_RENAMED", "payment", "healthy", 1));
        table.apply(event(REGISTERED, null, "healthy", 1));
        table.apply(new ServiceEvent(REGISTERED, "payment", "healthy", 1, null, "payment.compensate"));
        table.apply(new ServiceEvent(REGISTERED, "payment", "healthy", 1, "payment.in", " "));
        table.apply(null);

        assertThat(table.resolve("payment")).isEmpty();
    }

    static ServiceEvent event(String type, String name, String status, long generation) {
        return new ServiceEvent(type, name, status, generation, name + ".in", name + ".compensate");
    }
}
