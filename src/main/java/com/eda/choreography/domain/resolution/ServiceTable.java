package com.eda.choreography.domain.resolution;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A local table of services, rebuilt by replaying discovery's {@code service-events} stream
 * from the start. A service resolves to the topics on its latest event, and is live only while
 * its status is {@code healthy}: discovery's contract allows sending work on that value alone.
 *
 * <p>One consumer thread applies events while any number of steps resolve names, so entries are
 * replaced whole and never changed in place.
 */
public final class ServiceTable implements ResolutionSource {

    private final Map<String, Resolution> services = new ConcurrentHashMap<>();

    /** Applies one event. An event this table cannot use (an unknown type, a missing field) is ignored. */
    public void apply(ServiceEvent event) {
        if (!usable(event)) {
            return;
        }
        switch (event.type()) {
            case ServiceEvent.REGISTERED, ServiceEvent.STATUS_CHANGED -> services.put(
                    event.serviceName(),
                    new Resolution(event.inputTopic(), event.compensationTopic(), ServiceEvent.HEALTHY.equals(event.status())));
            default -> {
            }
        }
    }

    @Override
    public Optional<Resolution> resolve(String serviceName) {
        return Optional.ofNullable(services.get(serviceName));
    }

    private static boolean usable(ServiceEvent event) {
        return event != null
                && event.type() != null
                && hasText(event.serviceName())
                && hasText(event.inputTopic())
                && hasText(event.compensationTopic());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
