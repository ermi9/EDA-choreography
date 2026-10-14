package com.eda.choreography.infra.kafka;

/**
 * Where a service listens for its own work: discovery derives a service's topics from its name
 * as {@code <name>.in} and {@code <name>.compensate}, and a service subscribes before it has
 * registered. Every other service reaches it through discovery's table instead (see
 * {@link Destinations}), never through this convention.
 */
final class StepTopics {

    private StepTopics() {
    }

    static String inputTopic(String stepId) {
        return stepId + ".in";
    }

    static String compensationTopic(String stepId) {
        return stepId + ".compensate";
    }
}
