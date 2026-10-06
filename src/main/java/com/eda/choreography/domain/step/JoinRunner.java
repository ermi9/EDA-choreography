package com.eda.choreography.domain.step;

import com.eda.choreography.domain.compensation.CompensationTrigger;
import com.eda.choreography.domain.join.BranchArrival;
import com.eda.choreography.domain.join.JoinDeadlines;
import com.eda.choreography.domain.join.JoinKey;
import com.eda.choreography.domain.join.JoinOutcome.Decision;
import com.eda.choreography.domain.join.JoinState;
import com.eda.choreography.domain.join.JoinStateMachine;
import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.trace.TraceEntry;
import com.eda.choreography.domain.trace.TraceGraph;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The service that runs a join step: it collects the branches of a fork and runs the step once,
 * on their merged message, when the last one arrives. There is no coordinator; whichever branch
 * arrives first opens the join, and the join's state is all the service remembers.
 *
 * <p>When the join fires it either runs its step or, if any branch failed, undoes the instance.
 * The merged trace holds every branch, so the compensation reaches the successful branches too.
 *
 * <p>A join that waits too long is timed out. The first branch to arrive sets the join's
 * deadline; when it passes, a sweeper has {@link #timeOut} called for the instance. The branches
 * that did arrive are undone then, and a branch that arrives later undoes itself.
 */
public final class JoinRunner {

    private final StepRunner step;
    private final int expectedBranches;
    private final Duration timeout;
    private final JoinStateMachine joins;
    private final JoinDeadlines deadlines;
    private final CompensationTrigger compensations;
    private final Clock clock;

    /**
     * @param timeout how long the join waits for its last branch, counted from its first
     */
    public JoinRunner(
            StepRunner step,
            int expectedBranches,
            Duration timeout,
            JoinStateMachine joins,
            JoinDeadlines deadlines,
            CompensationTrigger compensations,
            Clock clock) {
        if (expectedBranches < 1) {
            throw new IllegalArgumentException("expectedBranches must be at least 1, was " + expectedBranches);
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, was " + timeout);
        }
        this.step = Objects.requireNonNull(step, "step");
        this.expectedBranches = expectedBranches;
        this.timeout = timeout;
        this.joins = Objects.requireNonNull(joins, "joins");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.compensations = Objects.requireNonNull(compensations, "compensations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String stepId() {
        return step.stepId();
    }

    /**
     * Takes one branch's message. A branch that arrives again after the join fired runs the
     * step again, because the first run may have stopped before handing on; the step's entry
     * id is derived from the merged trace, so both runs record the same entry.
     *
     * <p>While the join is open, every arrival (a redelivered one too) makes sure it has a
     * deadline, in case an earlier run stopped between opening the join and setting it.
     */
    public void handle(ChoreographyMessage branch) {
        arrive(branch, step::handle);
    }

    /**
     * Gives up on the join step for this branch's instance, after the adapter ran out of
     * retries. The branch is taken as in {@link #handle}, but once the join has fired the step
     * fails on the merged message instead of running (see {@link StepRunner#fail}). If the
     * retries ran out before the join fired, this simply tries the arrival once more.
     */
    public void fail(ChoreographyMessage branch) {
        arrive(branch, step::fail);
    }

    private void arrive(ChoreographyMessage branch, Consumer<ChoreographyMessage> whenFired) {
        var branchId = lastEntryOf(branch).id();
        var key = new JoinKey(branch.correlationId(), stepId());
        var outcome = joins.arrive(new BranchArrival(key, branchId, expectedBranches, branch));
        if (outcome.decision() == Decision.LATE) {
            compensations.trigger(branch, "join-late:" + branchId);
            return;
        }
        if (outcome.state().status() == JoinState.Status.OPEN) {
            deadlines.setIfAbsent(key, clock.instant().plus(timeout));
            return;
        }
        if (!outcome.state().fired()) {
            return;
        }
        deadlines.remove(key);
        var merged = merge(outcome.state());
        if (merged.hasFailed()) {
            compensations.trigger(merged, "join-failed:" + stepId());
            return;
        }
        whenFired.accept(merged);
    }

    /**
     * Gives up on this join for one instance and undoes the branches that arrived. Does nothing
     * if the join already fired or was never opened. Timing out again repeats the compensation,
     * which is harmless, so a caller that crashed half way can simply call again.
     */
    public void timeOut(String correlationId) {
        joins.timeOut(new JoinKey(correlationId, stepId()))
                .filter(state -> !state.branchMessages().isEmpty())
                .ifPresent(state -> compensations.trigger(merge(state), "join-timeout:" + stepId()));
    }

    /** Branches are merged in branch id order, so every run of the join merges them alike. */
    private static ChoreographyMessage merge(JoinState state) {
        return ChoreographyMessage.merge(state.branchMessages().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList());
    }

    /** A branch is handed to the join by the step that ended it, so its trace has exactly one leaf. */
    private TraceEntry lastEntryOf(ChoreographyMessage branch) {
        var leaves = TraceGraph.of(branch.trace()).leaves();
        if (leaves.size() != 1) {
            throw new IllegalArgumentException("join " + stepId() + " was handed a branch of "
                    + branch.correlationId() + " that ends in " + leaves.size() + " entries");
        }
        return leaves.iterator().next();
    }
}
