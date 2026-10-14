package com.eda.choreography.domain.step;

import com.eda.choreography.domain.compensation.CompensationTrigger;
import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * One service's part in a choreography: consume a message, do the step, append it to the
 * trace, and hand the message on. There is no coordinator; each step decides its own next hop.
 *
 * <p>A runner belongs to one service. The service may run several steps of a flow, all fed from
 * its one topic, so it runs the step each message is addressed to, or the step named after the
 * service when the message names none. It hands the message on to the service of each next
 * step, addressed to that step.
 *
 * <p>Before handing on, the runner checks that each next step's service takes work. If one does
 * not (discovery does not know it yet, or it is not live), nothing is published and
 * {@link NextHopUnavailableException} is thrown, so the adapter can wait for a late-bound
 * service to appear.
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
    private final Predicate<String> takesWork;

    /** A runner that hands on to every service without asking whether it takes work. */
    public StepRunner(
            String stepId,
            StepAction action,
            NextSteps nextSteps,
            MessagePublisher publisher,
            CompensationTrigger compensations) {
        this(stepId, action, nextSteps, publisher, compensations, service -> true);
    }

    /**
     * @param takesWork whether a service may be handed work now, such as discovery reporting it live
     */
    public StepRunner(
            String stepId,
            StepAction action,
            NextSteps nextSteps,
            MessagePublisher publisher,
            CompensationTrigger compensations,
            Predicate<String> takesWork) {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId must be non-blank");
        }
        this.stepId = stepId;
        this.action = Objects.requireNonNull(action, "action");
        this.nextSteps = Objects.requireNonNull(nextSteps, "nextSteps");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.compensations = Objects.requireNonNull(compensations, "compensations");
        this.takesWork = Objects.requireNonNull(takesWork, "takesWork");
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
        var waitingFor = next.stream()
                .map(target -> nextSteps.serviceOf(target, outgoing))
                .filter(takesWork.negate())
                .toList();
        if (!waitingFor.isEmpty()) {
            throw new NextHopUnavailableException(step, waitingFor);
        }
        for (var target : next) {
            handOn(target, outgoing);
        }
    }

    /**
     * Gives up waiting for the services of some next steps, after the adapter waited as long as a
     * late-bound service may take to appear. The step ran and committed, so it is recorded as
     * done; each next step whose service takes no work is recorded as failed instead of run.
     * Inside a fork that failure goes to the join, and the other branches go on as usual; anywhere
     * else the instance is undone, this step included.
     *
     * <p>The step runs once more here, as it did on every attempt; a step's work is idempotent,
     * as at-least-once delivery requires anyway.
     */
    public void failNextHop(ChoreographyMessage incoming) {
        var step = stepOf(incoming);
        var outgoing = incoming.recordStep(step, stepId, action.execute(incoming));
        for (var target : nextSteps.after(step, outgoing)) {
            var service = nextSteps.serviceOf(target, outgoing);
            if (takesWork.test(service)) {
                handOn(target, outgoing);
            } else {
                reportFailure(target, outgoing.recordFailure(target, service));
            }
        }
    }

    private void handOn(String target, ChoreographyMessage outgoing) {
        publisher.publish(nextSteps.serviceOf(target, outgoing), outgoing.addressedTo(target));
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
        reportFailure(step, incoming.recordFailure(step, stepId));
    }

    /** A failed step inside a fork goes on to its join; anywhere else the instance is undone. */
    private void reportFailure(String step, ChoreographyMessage failed) {
        var join = nextSteps.enclosingJoin(step, failed);
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
