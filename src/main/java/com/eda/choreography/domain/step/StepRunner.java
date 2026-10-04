package com.eda.choreography.domain.step;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.Objects;

/**
 * One service's part in a choreography: consume a message, do the step, append it to the
 * trace, and hand the message on. There is no coordinator; each step decides its own next hop.
 *
 * <p>The runner knows nothing about transport. An adapter delivers messages to
 * {@link #handle} and implements {@link MessagePublisher}.
 */
public final class StepRunner {

    private final String stepId;
    private final StepAction action;
    private final NextSteps nextSteps;
    private final MessagePublisher publisher;

    public StepRunner(String stepId, StepAction action, NextSteps nextSteps, MessagePublisher publisher) {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId must be non-blank");
        }
        this.stepId = stepId;
        this.action = Objects.requireNonNull(action, "action");
        this.nextSteps = Objects.requireNonNull(nextSteps, "nextSteps");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    public String stepId() {
        return stepId;
    }

    /**
     * Runs the step on one incoming message. If the step throws, nothing is published and the
     * exception reaches the adapter, which decides about redelivery.
     */
    public void handle(ChoreographyMessage incoming) {
        var outgoing = incoming.recordStep(stepId, action.execute(incoming));
        var next = nextSteps.after(stepId, outgoing);
        if (next.isEmpty()) {
            publisher.publishCompleted(outgoing);
            return;
        }
        for (var target : next) {
            publisher.publish(target, outgoing);
        }
    }
}
