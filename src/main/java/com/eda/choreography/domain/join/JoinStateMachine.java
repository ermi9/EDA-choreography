package com.eda.choreography.domain.join;

import com.eda.choreography.domain.join.JoinOutcome.Decision;

/**
 * Applies branch arrivals to joins and decides when a join fires.
 *
 * <p>A join fires exactly once: when the set of distinct arrived branches first reaches
 * {@code expectedBranches}. Duplicates, before or after firing, change nothing.
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

        var next = state.withArrival(arrival.branchId(), arrival.message());
        if (next.isComplete()) {
            next = next.markFired();
            store.save(next);
            return new JoinOutcome(Decision.FIRED, next);
        }
        store.save(next);
        return new JoinOutcome(Decision.WAITING, next);
    }
}
