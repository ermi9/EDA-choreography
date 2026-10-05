package com.eda.choreography.domain.compensation;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.trace.TraceGraph;
import java.util.Objects;

/**
 * Starts undoing an instance: asks every entry of the first compensation stage to undo itself.
 * The later stages are reached by the compensators themselves (see {@link CompensationRunner}).
 */
public final class CompensationTrigger {

    private final CompensationPublisher publisher;

    public CompensationTrigger(CompensationPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    /**
     * @param instance the instance to undo, with everything that happened to it in its trace
     * @param runId    names this compensation; the same instance and run id always start the same
     *                 requests, so triggering twice is harmless
     */
    public void trigger(ChoreographyMessage instance, String runId) {
        var order = CompensationOrdering.of(TraceGraph.of(instance.trace()));
        if (order.isEmpty()) {
            publisher.publishCompensated(instance);
            return;
        }
        for (var entry : order.stages().get(0)) {
            publisher.publish(entry.stepId(), new CompensationRequest(runId, entry.id(), runId, instance));
        }
    }
}
