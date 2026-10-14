package com.eda.choreography.domain.resolution;

/**
 * This engine's own view of one event on discovery's {@code service-events} topic: only the
 * fields of the published contract that resolution needs. The producer's event has more
 * fields, which are ignored.
 *
 * <p>{@code type} and {@code status} stay strings, as discovery recommends, so a value added
 * later still reads as an event instead of failing the consumer.
 *
 * @param type              {@code SERVICE_REGISTERED}, {@code SERVICE_DEREGISTERED} or {@code STATUS_CHANGED}
 * @param serviceName       the logical name, also the record key
 * @param status            {@code healthy}, {@code not-ready}, {@code unavailable} or {@code unknown}
 * @param generation        the fencing token: a status change below the highest seen is stale
 * @param inputTopic        where the service consumes its steps
 * @param compensationTopic where the service consumes its undo requests
 */
public record ServiceEvent(
        String type,
        String serviceName,
        String status,
        long generation,
        String inputTopic,
        String compensationTopic) {

    public static final String REGISTERED = "SERVICE_REGISTERED";
    public static final String DEREGISTERED = "SERVICE_DEREGISTERED";
    public static final String STATUS_CHANGED = "STATUS_CHANGED";
    public static final String HEALTHY = "healthy";
}
