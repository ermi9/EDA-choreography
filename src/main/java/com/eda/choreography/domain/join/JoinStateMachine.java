package com.eda.choreography.domain.join;

import com.eda.choreography.domain.join.JoinOutcome.Decision;

/**
 * Applies branch arrivals to joins and decides when a join fires.
 *
 * <p>A join fires exactly once: when the set of distinct arrived branches first reaches
 * {@code expectedBranches}.
 */
public final class JoinStateMachine {

    private final JoinStateStore store;

    public JoinStateMachine(JoinStateStore store) {
        this.store = store;
    }

    /** Records one arrival and reports what it did. */
    public JoinOutcome arrive(BranchArrival arrival) {
        var state = store.find(arrival.key())
                .orElseGet(() -> JoinState.open(arrival.key(), arrival.expectedBranches()));
        var next = state.withArrival(arrival.branchId());
        if (next.isComplete()) {
            next = next.markFired();
            store.save(next);
            return new JoinOutcome(Decision.FIRED, next);
        }
        store.save(next);
        return new JoinOutcome(Decision.WAITING, next);
    }
}
