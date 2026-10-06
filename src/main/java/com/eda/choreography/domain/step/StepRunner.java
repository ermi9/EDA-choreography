package com.eda.choreography.domain.step;

import com.eda.choreography.domain.compensation.CompensationTrigger;
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
    private final CompensationTrigger compensations;

    public StepRunner(
            String stepId,
            StepAction action,
            NextSteps nextSteps,
            MessagePublisher publisher,
            CompensationTrigger compensations) {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId must be non-blank");
        }
        this.stepId = stepId;
        this.action = Objects.requireNonNull(action, "action");
        this.nextSteps = Objects.requireNonNull(nextSteps, "nextSteps");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.compensations = Objects.requireNonNull(compensations, "compensations");
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

    /**
     * Gives up on the step for this message, after the adapter ran out of retries. The failure
     * is recorded in the trace. Inside a fork it travels on to the join like any branch end, so
     * the join can wait for the other branches; anywhere else the instance is undone at once.
     *
     * <p>The compensation is named after the failed entry, so giving up twice on the same
     * message sends the same requests again, and undoing is idempotent.
     */
    public void fail(ChoreographyMessage incoming) {
        var failed = incoming.recordFailure(stepId);
        var join = nextSteps.enclosingJoin(stepId);
        if (join.isPresent()) {
            publisher.publish(join.get(), failed);
            return;
        }
        compensations.trigger(failed, failed.trace().get(failed.trace().size() - 1).id());
    }
}
