package com.eda.choreography.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.resolution.ServiceEvent;
import com.eda.choreography.domain.resolution.ServiceTable;
import org.junit.jupiter.api.Test;

class ServiceBindingsTest {

    private final ServiceTable catalog = new ServiceTable();

    @Test
    void aPlanWhoseServicesDiscoveryKnowsIsFullyBound() {
        for (var service : new String[] {"reserve", "pay", "ship", "confirm", "notify"}) {
            register(service, "healthy");
        }

        var bindings = ServiceBindings.of(PlanModelTest.checkout(), catalog);

        assertThat(bindings.fullyBound()).isTrue();
        assertThat(bindings.bound()).containsExactlyInAnyOrder("reserve", "pay", "ship", "confirm", "notify");
    }

    @Test
    void aServiceDiscoveryDoesNotKnowYetIsLateBoundAndOneThatIsDownIsStillBound() {
        register("reserve", "healthy");
        register("pay", "unavailable");
        register("confirm", "healthy");
        register("notify", "healthy");

        var bindings = ServiceBindings.of(PlanModelTest.checkout(), catalog);

        assertThat(bindings.fullyBound()).isFalse();
        assertThat(bindings.lateBound()).containsExactly("ship");
        assertThat(bindings.bound()).contains("pay");
    }

    private void register(String service, String status) {
        catalog.apply(new ServiceEvent(ServiceEvent.STATUS_CHANGED, service, status, 1, service + ".in", service + ".compensate"));
    }
}
