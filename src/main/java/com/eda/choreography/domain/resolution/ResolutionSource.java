package com.eda.choreography.domain.resolution;

import java.util.Optional;

/**
 * Resolves a logical service name to its {@link Resolution}. The runtime depends on this port,
 * never on where the answers come from.
 */
@FunctionalInterface
public interface ResolutionSource {

    /**
     * The service's topics and liveness, or empty if the service is not known. A service that
     * deregistered stays known, but not live, so the steps it already ran can still be undone.
     */
    Optional<Resolution> resolve(String serviceName);
}
