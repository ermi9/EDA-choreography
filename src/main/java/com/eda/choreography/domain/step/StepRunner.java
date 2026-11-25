package com.eda.choreography.domain.step;

import com.eda.choreography.domain.compensation.CompensationTrigger;
import com.eda.choreography.domain.join.ForkSkips;
import com.eda.choreography.domain.join.JoinKey;
import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.Objects;
import java.util.Optional;
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
 * <p>A step that has already run as often as its flow allows fails instead of running again, so
 * a loop whose way out never comes ends, and is undone like any other failure.
 *
 * <p>Inside a fork, a step first asks whether the fork has already failed (see {@link ForkSkips}).
 * If it has, the step does no work: it records itself as skipped and hands the instance straight
 * to its join, which undoes it. A step inside a fork that fails marks the fork failed before it
 * reports to the join, so its siblings stop as early as they can.
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
    private final ForkSkips forks;

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
     * A runner whose forks run every branch to its end.
     *
     * @param takesWork whether a service may be handed work now, such as discovery reporting it live
     */
    public StepRunner(
            String stepId,
            StepAction action,
            NextSteps nextSteps,
            MessagePublisher publisher,
            CompensationTrigger compensations,
            Predicate<String> takesWork) {
        this(stepId, action, nextSteps, publisher, compensations, takesWork, ForkSkips.NONE);
    }

    /**
     * @param takesWork whether a service may be handed work now, such as discovery reporting it live
     * @param forks     where a fork's failure is shared with its branches
     */
    public StepRunner(
            String stepId,
            StepAction action,
            NextSteps nextSteps,
            MessagePublisher publisher,
            CompensationTrigger compensations,
            Predicate<String> takesWork,
            ForkSkips forks) {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId must be non-blank");
        }
        this.stepId = stepId;
        this.action = Objects.requireNonNull(action, "action");
        this.nextSteps = Objects.requireNonNull(nextSteps, "nextSteps");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.compensations = Objects.requireNonNull(compensations, "compensations");
        this.takesWork = Objects.requireNonNull(takesWork, "takesWork");
        this.forks = Objects.requireNonNull(forks, "forks");
    }

    /** The service this runner belongs to, which is also the step it runs when a message names none. */
    public String stepId() {
        return stepId;
    }

    /**
     * Runs the step on one incoming message. If the step throws, nothing is published and the
     * exception reaches the adapter, which decides about redelivery. An exception after the step's
     * work was done arrives as an {@link UncertainOutcomeException}, so giving up undoes that work.
     */
    public void handle(ChoreographyMessage incoming) {
        var step = stepOf(incoming);
        var join = nextSteps.enclosingJoin(step, incoming);
        if (join.isPresent() && forks.skips(forkOf(incoming, join.get()), incoming.entryIdFor(step))) {
            var skipped = incoming.recordSkip(step, stepId);
            publisher.publish(nextSteps.serviceOf(join.get(), skipped), skipped.addressedTo(join.get()));
            return;
        }
        if (incoming.runsOf(step) >= nextSteps.maxRuns(step, incoming)) {
            reportFailure(step, incoming.recordFailure(step, stepId));
            return;
        }
        var result = action.execute(incoming);
        try {
            handOn(step, incoming.recordStep(step, stepId, result));
        } catch (NextHopUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UncertainOutcomeException("step " + step + " ran but could not be handed on", e);
        }
    }

    private void handOn(String step, ChoreographyMessage outgoing) {
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
            publishTo(target, outgoing);
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
                publishTo(target, outgoing);
            } else {
                reportFailure(target, outgoing.recordFailure(target, service));
            }
        }
    }

    private void publishTo(String target, ChoreographyMessage outgoing) {
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

    /**
     * Gives up on the step as {@link #fail} does, when its work may have happened all the same.
     * The step is recorded as uncertain, so the compensation asks its service to undo it before
     * anything earlier is undone.
     */
    public void failUncertain(ChoreographyMessage incoming) {
        var step = stepOf(incoming);
        reportFailure(step, incoming.recordUncertain(step, stepId));
    }

    /**
     * A failed step inside a fork marks the fork failed and goes on to its join; anywhere else the
     * instance is undone. The mark comes first, so the siblings hear of it as soon as possible.
     */
    private void reportFailure(String step, ChoreographyMessage failed) {
        var join = nextSteps.enclosingJoin(step, failed);
        if (join.isPresent()) {
            forks.markFailed(forkOf(failed, join.get()));
            publisher.publish(nextSteps.serviceOf(join.get(), failed), failed.addressedTo(join.get()));
            return;
        }
        compensations.trigger(failed, failed.trace().get(failed.trace().size() - 1).id());
    }

    /** The join a step of this message's flow ends its branch at, if it is inside a fork. */
    Optional<String> enclosingJoin(String step, ChoreographyMessage message) {
        return nextSteps.enclosingJoin(step, message);
    }

    /** Marks the fork closed by {@code fork} failed, for a join that gives up on it. */
    void markForkFailed(JoinKey fork) {
        forks.markFailed(fork);
    }

    private static JoinKey forkOf(ChoreographyMessage message, String join) {
        return new JoinKey(message.correlationId(), join);
    }

    private String stepOf(ChoreographyMessage incoming) {
        return incoming.step() != null ? incoming.step() : stepId;
    }
}
