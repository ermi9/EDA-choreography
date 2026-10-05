package com.eda.choreography.infra.kafka;

/**
 * Where a step listens. Follows discovery's {@code <name>.in} and {@code <name>.compensate}
 * conventions directly, because there is no discovery yet. INC-5 resolves names through a
 * {@code ResolutionSource} instead.
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
