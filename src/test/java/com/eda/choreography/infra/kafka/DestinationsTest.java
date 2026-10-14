package com.eda.choreography.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.resolution.Resolution;
import com.eda.choreography.domain.resolution.UnresolvedServiceException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DestinationsTest {

    private final Destinations destinations = new Destinations(name -> Optional.ofNullable(Map.of(
            "payment", new Resolution("billing.in", "billing.undo", true),
            "shipping", new Resolution("shipping.in", "shipping.compensate", false)).get(name)));

    @Test
    void sendsWorkToTheTopicDiscoveryGaveALiveService() {
        assertThat(destinations.workFor("payment")).isEqualTo("billing.in");
    }

    @Test
    void sendsNoWorkToAServiceThatIsNotLiveOrNotKnown() {
        assertThatThrownBy(() -> destinations.workFor("shipping"))
                .isInstanceOf(UnresolvedServiceException.class)
                .hasMessageContaining("shipping");
        assertThatThrownBy(() -> destinations.workFor("tax"))
                .isInstanceOf(UnresolvedServiceException.class)
                .hasMessageContaining("tax");
    }

    @Test
    void undoRequestsAndTimeoutNoticesOnlyNeedTheServiceToBeKnown() {
        assertThat(destinations.undoFor("payment")).isEqualTo("billing.undo");
        assertThat(destinations.undoFor("shipping")).isEqualTo("shipping.compensate");
        assertThat(destinations.timeoutNoticeFor("shipping")).isEqualTo("shipping.in");
        assertThatThrownBy(() -> destinations.undoFor("tax")).isInstanceOf(UnresolvedServiceException.class);
        assertThatThrownBy(() -> destinations.timeoutNoticeFor("tax")).isInstanceOf(UnresolvedServiceException.class);
    }
}
