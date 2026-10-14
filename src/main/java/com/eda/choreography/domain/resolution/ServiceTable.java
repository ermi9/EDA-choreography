package com.eda.choreography.domain.resolution;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A local table of services, rebuilt by replaying discovery's {@code service-events} stream
 * from the start. A service resolves to the topics on its latest event, and is live only while
 * its status is {@code healthy}: discovery's contract allows sending work on that value alone.
 *
 * <p>Status changes are fenced by generation, as the contract asks. Kafka delivers at least
 * once and discovery's partition leadership can move, so a status change from a deposed leader
 * can arrive after a newer one; one below the highest generation seen for the service is
 * dropped. A registration starts a new lifecycle and resets the fence.
 *
 * <p>A deregistered service stays in the table, not live, so the steps it ran can still be
 * undone through its compensation topic. Discovery deleted it, so only a new registration
 * brings it back; a status change after the deregistration is a late one from before.
 *
 * <p>One consumer thread applies events while any number of steps resolve names, so entries are
 * replaced whole and never changed in place.
 */
public final class ServiceTable implements ResolutionSource {

    private final Map<String, Entry> services = new ConcurrentHashMap<>();

    /** Applies one event. An event this table cannot use (an unknown type, a missing field) is ignored. */
    public void apply(ServiceEvent event) {
        if (!usable(event)) {
            return;
        }
        switch (event.type()) {
            case ServiceEvent.REGISTERED -> services.put(event.serviceName(), Entry.of(event, false));
            case ServiceEvent.DEREGISTERED -> services.put(event.serviceName(), Entry.of(event, true));
            case ServiceEvent.STATUS_CHANGED -> services.compute(event.serviceName(), (name, known) ->
                    known == null || (!known.deregistered && event.generation() >= known.generation)
                            ? Entry.of(event, false)
                            : known);
            default -> {
            }
        }
    }

    /**
     * Every service the table knows, by name, deregistered ones left out: what a flow can be
     * drawn with right now. A snapshot; events applied later do not change it.
     */
    public SortedMap<String, Resolution> services() {
        var known = new TreeMap<String, Resolution>();
        services.forEach((name, entry) -> {
            if (!entry.deregistered()) {
                known.put(name, entry.resolution());
            }
        });
        return Collections.unmodifiableSortedMap(known);
    }

    @Override
    public Optional<Resolution> resolve(String serviceName) {
        return Optional.ofNullable(services.get(serviceName)).map(Entry::resolution);
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

    private record Entry(Resolution resolution, long generation, boolean deregistered) {

        static Entry of(ServiceEvent event, boolean deregistered) {
            var live = !deregistered && ServiceEvent.HEALTHY.equals(event.status());
            return new Entry(
                    new Resolution(event.inputTopic(), event.compensationTopic(), live),
                    event.generation(),
                    deregistered);
        }
    }
}
