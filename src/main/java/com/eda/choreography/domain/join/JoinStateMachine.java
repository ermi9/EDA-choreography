package com.eda.choreography.domain.join;

import com.eda.choreography.domain.join.JoinOutcome.Decision;
import java.util.Optional;

/**
 * Applies branch arrivals to joins and decides when a join fires.
 *
 * <p>A join fires exactly once: when the set of distinct arrived branches first reaches
 * {@code expectedBranches}. Duplicates, before or after firing, change nothing.
 *
 * <p>A join that waits too long can be timed out instead. It then never fires, and a branch
 * that only reaches it afterwards is reported as {@link Decision#LATE}.
 */
public final class JoinStateMachine {

    private final JoinStateStore store;

    public JoinStateMachine(JoinStateStore store) {
        this.store = store;
    }

    /**
     * Records one arrival and reports what it did.
     *
     * @throws JoinProtocolException if the arrival disagrees with the join on how many branches
     *     are expected, or is a new branch for a join that already has them all; the stored state
     *     is left unchanged
     */
    public JoinOutcome arrive(BranchArrival arrival) {
        var state = store.find(arrival.key())
                .orElseGet(() -> JoinState.open(arrival.key(), arrival.expectedBranches()));
        if (state.expectedBranches() != arrival.expectedBranches()) {
            throw new JoinProtocolException("join " + arrival.key() + " expects " + state.expectedBranches()
                    + " branches but branch " + arrival.branchId() + " says expectedBranches="
                    + arrival.expectedBranches());
        }
        if (state.arrivedBranches().contains(arrival.branchId())) {
            return new JoinOutcome(Decision.DUPLICATE, state);
        }
        if (state.status() == JoinState.Status.TIMED_OUT) {
            return new JoinOutcome(Decision.LATE, state);
        }

        var next = state.withArrival(arrival.branchId(), arrival.message());
        if (next.isComplete()) {
            next = next.markFired();
            store.save(next);
            return new JoinOutcome(Decision.FIRED, next);
        }
        store.save(next);
        return new JoinOutcome(Decision.WAITING, next);
    }

    /**
     * Gives up on a join that is still waiting.
     *
     * @return the timed-out state, with the messages of the branches that did arrive; the same
     *     state again if the join had already timed out, so a caller that crashed half way can
     *     finish; empty if the join fired or was never opened
     */
    public Optional<JoinState> timeOut(JoinKey key) {
        var state = store.find(key);
        if (state.isEmpty() || state.get().fired()) {
            return Optional.empty();
        }
        if (state.get().status() == JoinState.Status.TIMED_OUT) {
            return state;
        }
        var timedOut = state.get().markTimedOut();
        store.save(timedOut);
        return Optional.of(timedOut);
    }
}
