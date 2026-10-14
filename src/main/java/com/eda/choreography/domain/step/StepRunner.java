package com.eda.choreography.domain.step;

import com.eda.choreography.domain.compensation.CompensationTrigger;
import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.Objects;

/**
 * One service's part in a choreography: consume a message, do the step, append it to the
 * trace, and hand the message on. There is no coordinator; each step decides its own next hop.
 *
 * <p>A runner belongs to one service. The service may run several steps of a flow, all fed from
 * its one topic, so it runs the step each message is addressed to, or the step named after the
 * service when the message names none. It hands the message on to the service of each next
 * step, addressed to that step.
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

    /** The service this runner belongs to, which is also the step it runs when a message names none. */
    public String stepId() {
        return stepId;
    }

    /**
     * Runs the step on one incoming message. If the step throws, nothing is published and the
     * exception reaches the adapter, which decides about redelivery.
     */
    public void handle(ChoreographyMessage incoming) {
        var step = stepOf(incoming);
        var outgoing = incoming.recordStep(step, stepId, action.execute(incoming));
        var next = nextSteps.after(step, outgoing);
        if (next.isEmpty()) {
            publisher.publishCompleted(outgoing);
            return;
        }
        for (var target : next) {
            publisher.publish(nextSteps.serviceOf(target, outgoing), outgoing.addressedTo(target));
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
        var step = stepOf(incoming);
        var failed = incoming.recordFailure(step, stepId);
        var join = nextSteps.enclosingJoin(step, incoming);
        if (join.isPresent()) {
            publisher.publish(nextSteps.serviceOf(join.get(), failed), failed.addressedTo(join.get()));
            return;
        }
        compensations.trigger(failed, failed.trace().get(failed.trace().size() - 1).id());
    }

    private String stepOf(ChoreographyMessage incoming) {
        return incoming.step() != null ? incoming.step() : stepId;
    }
}
