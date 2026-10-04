package com.eda.choreography.infra.kafka;

/**
 * Where a step listens. Follows discovery's {@code <name>.in} convention directly, because
 * INC-3 has no discovery yet. INC-5 resolves names through a {@code ResolutionSource} instead.
 */
final class StepTopics {

    private StepTopics() {
    }

    static String inputTopic(String stepId) {
        return stepId + ".in";
    }
}
