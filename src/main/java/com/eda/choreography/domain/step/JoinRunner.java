package com.eda.choreography.domain.step;

import com.eda.choreography.domain.compensation.CompensationTrigger;
import com.eda.choreography.domain.join.BranchArrival;
import com.eda.choreography.domain.join.JoinKey;
import com.eda.choreography.domain.join.JoinOutcome.Decision;
import com.eda.choreography.domain.join.JoinState;
import com.eda.choreography.domain.join.JoinStateMachine;
import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.trace.TraceEntry;
import com.eda.choreography.domain.trace.TraceGraph;
import java.util.Map;
import java.util.Objects;

/**
 * The service that runs a join step: it collects the branches of a fork and runs the step once,
 * on their merged message, when the last one arrives. There is no coordinator; whichever branch
 * arrives first opens the join, and the join's state is all the service remembers.
 *
 * <p>When the join fires it either runs its step or, if any branch failed, undoes the instance.
 * The merged trace holds every branch, so the compensation reaches the successful branches too.
 *
 * <p>A join that waits too long is timed out from outside (see {@link #timeOut}). The branches
 * that did arrive are undone then, and a branch that arrives later undoes itself.
 */
public final class JoinRunner {

    private final StepRunner step;
    private final int expectedBranches;
    private final JoinStateMachine joins;
    private final CompensationTrigger compensations;

    public JoinRunner(
            StepRunner step, int expectedBranches, JoinStateMachine joins, CompensationTrigger compensations) {
        if (expectedBranches < 1) {
            throw new IllegalArgumentException("expectedBranches must be at least 1, was " + expectedBranches);
        }
        this.step = Objects.requireNonNull(step, "step");
        this.expectedBranches = expectedBranches;
        this.joins = Objects.requireNonNull(joins, "joins");
        this.compensations = Objects.requireNonNull(compensations, "compensations");
    }

    public String stepId() {
        return step.stepId();
    }

    /**
     * Takes one branch's message. A branch that arrives again after the join fired runs the
     * step again, because the first run may have stopped before handing on; the step's entry
     * id is derived from the merged trace, so both runs record the same entry.
     */
    public void handle(ChoreographyMessage branch) {
        var branchId = lastEntryOf(branch).id();
        var key = new JoinKey(branch.correlationId(), stepId());
        var outcome = joins.arrive(new BranchArrival(key, branchId, expectedBranches, branch));
        if (outcome.decision() == Decision.LATE) {
            compensations.trigger(branch, "join-late:" + branchId);
            return;
        }
        if (!outcome.state().fired()) {
            return;
        }
        var merged = merge(outcome.state());
        if (merged.hasFailed()) {
            compensations.trigger(merged, "join-failed:" + stepId());
            return;
        }
        step.handle(merged);
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
