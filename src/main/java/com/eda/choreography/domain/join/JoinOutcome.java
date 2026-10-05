package com.eda.choreography.domain.join;

/**
 * What one arrival did to its join.
 *
 * @param decision what the caller must do
 * @param state    the join's state after the arrival
 */
public record JoinOutcome(Decision decision, JoinState state) {

    public enum Decision {
        /** The arrival was new, but other branches are still outstanding. */
        WAITING,
        /** The arrival completed the join. Reported exactly once per join. */
        FIRED,
        /** This branch had already arrived; nothing changed. */
        DUPLICATE,
        /** A new branch reached a join that had already timed out; nothing changed. */
        LATE
    }

    public boolean fired() {
        return decision == Decision.FIRED;
    }
}
