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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The service that runs a join step: it collects the branches of a fork and runs the step once,
 * on their merged message, when the last one arrives. There is no coordinator; whichever branch
 * arrives first opens the join, and the join's state is all the service remembers.
 *
 * <p>When the join fires it either runs its step or, if any branch failed or was skipped, undoes
 * the instance. The merged trace holds every branch, so the compensation reaches the successful
 * branches too. A skipped step means its fork was marked failed, so the instance is undone even
 * when the failure that marked it was later retried into a success: a branch cut short must
 * never let the flow carry on without its work.
 *
 * <p>A branch is known by the step it started with after the fork, not by its last entry. One
 * branch can reach the join more than once, ending differently each time (completed, failed or
 * skipped, after a redelivery), and must still count once. A copy that arrives after the join
 * fired or timed out with entries the join never saw has those entries undone on their own.
 *
 * <p>A join that waits too long is timed out. The first branch to arrive sets the join's
 * deadline; when it passes, a sweeper has {@link #timeOut} called for the instance. The branches
 * that did arrive are undone then, and a branch that arrives later undoes only what the
 * timeout did not: its own steps after the fork.
 */
public final class JoinRunner {

    private final StepRunner step;
    private final JoinWaits waits;
    private final JoinStateMachine joins;
    private final JoinDeadlines deadlines;
    private final CompensationTrigger compensations;
    private final Clock clock;

    /**
     * A join that waits the same way in every flow.
     *
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
        this(step, JoinWaits.fixed(expectedBranches, timeout), joins, deadlines, compensations, clock);
    }

    /** A join that waits as the flow of each branch says. */
    public JoinRunner(
            StepRunner step,
            JoinWaits waits,
            JoinStateMachine joins,
            JoinDeadlines deadlines,
            CompensationTrigger compensations,
            Clock clock) {
        this.step = Objects.requireNonNull(step, "step");
        this.waits = Objects.requireNonNull(waits, "waits");
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

    /**
     * Gives up waiting for the service after the join step, after the adapter waited as long as a
     * late-bound service may take to appear (see {@link StepRunner#failNextHop}).
     */
    public void failNextHop(ChoreographyMessage branch) {
        arrive(branch, step::failNextHop);
    }

    private void arrive(ChoreographyMessage branch, Consumer<ChoreographyMessage> whenFired) {
        var leaf = lastEntryOf(branch);
        var key = new JoinKey(branch.correlationId(), stepId());
        if (branch.hasFailed()) {
            // The failed step marked the fork already, unless it could not reach the flag.
            step.markForkFailed(key);
        }
        var wait = waits.forJoin(branch.flowName(), stepId());
        var outcome = joins.arrive(new BranchArrival(key, branchOf(branch, leaf), wait.expectedBranches(), branch));
        if (outcome.decision() == Decision.LATE) {
            compensations.trigger(branch, "join-late:" + leaf.id(), undoneByTimeout(outcome.state()));
            return;
        }
        if (outcome.state().status() == JoinState.Status.OPEN) {
            deadlines.setIfAbsent(key, clock.instant().plus(wait.timeout()));
            return;
        }
        if (outcome.decision() == Decision.DUPLICATE) {
            undoWhatTheJoinNeverSaw(branch, leaf, outcome.state());
        }
        if (!outcome.state().fired()) {
            return;
        }
        deadlines.remove(key);
        var merged = merge(outcome.state());
        if (merged.hasFailed() || merged.hasSkipped()) {
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
        var key = new JoinKey(correlationId, stepId());
        joins.timeOut(key).ifPresent(state -> {
            // The branches still on their way are skipped from here on instead of arriving late.
            step.markForkFailed(key);
            if (!state.branchMessages().isEmpty()) {
                compensations.trigger(merge(state), "join-timeout:" + stepId());
            }
        });
    }

    /**
     * Undoes the steps that a copy of an arrived branch ran but the join's merged trace lacks,
     * because the copy came after the join fired or timed out. The rest of the trace is left out:
     * the join already undid it, or carried it on. The run is named after the copy's last entry,
     * so a redelivered copy sends the same requests again.
     */
    private void undoWhatTheJoinNeverSaw(ChoreographyMessage copy, TraceEntry leaf, JoinState state) {
        if (state.branchMessages().isEmpty()) {
            return;
        }
        var seen = merge(state).trace().stream().map(TraceEntry::id).collect(Collectors.toUnmodifiableSet());
        var unseenWork = copy.trace().stream()
                .anyMatch(entry -> !seen.contains(entry.id()) && entry.needsCompensation());
        if (unseenWork) {
            compensations.trigger(copy, "join-copy:" + leaf.id(), seen);
        }
    }

    /**
     * The step a branch started with after the fork: the branch's one entry whose step is inside
     * this join's fork while none of its parents' steps are. It stays the same however the branch
     * ended, and a loop inside the branch cannot repeat it. Routing that cannot say which steps
     * are inside the fork leaves only the branch's last entry to go by.
     */
    private String branchOf(ChoreographyMessage branch, TraceEntry leaf) {
        var trace = TraceGraph.of(branch.trace());
        // Asked once per step: the plan may be read from a store on every lookup.
        var inside = new HashMap<String, Boolean>();
        Predicate<TraceEntry> insideThisFork = entry -> inside.computeIfAbsent(entry.stepId(),
                stepId -> step.enclosingJoin(stepId, branch).filter(stepId()::equals).isPresent());
        var firstSteps = branch.trace().stream()
                .filter(insideThisFork)
                .filter(entry -> trace.parentsOf(entry.id()).stream().noneMatch(insideThisFork))
                .map(TraceEntry::stepId)
                .collect(Collectors.toUnmodifiableSet());
        return firstSteps.size() == 1 ? "step:" + firstSteps.iterator().next() : leaf.id();
    }

    /**
     * What the join's timeout compensation undoes: every entry of the branches that had
     * arrived, which includes the steps before the fork. A late branch leaves those out and
     * undoes only its own steps.
     */
    private static Set<String> undoneByTimeout(JoinState timedOut) {
        if (timedOut.branchMessages().isEmpty()) {
            return Set.of();
        }
        return merge(timedOut).trace().stream().map(TraceEntry::id).collect(Collectors.toUnmodifiableSet());
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
