package com.eda.choreography.domain.join;

import java.util.HashSet;
import java.util.Set;

/**
 * The accumulated state of one join.
 *
 * <p>Arrivals are held as a <em>set</em> of branch ids, not a counter: delivery is
 * at-least-once, so the same branch can arrive twice, and a counter would count it twice and
 * fire before the other branches are done. The set makes a repeat a no-op.
 *
 * <p>{@code fired} is kept after the join completes so that a late duplicate is recognised as a
 * duplicate instead of opening a fresh join that could never complete.
 *
 * @param key              the join
 * @param expectedBranches how many distinct branches complete the join
 * @param arrivedBranches  the distinct branch ids seen so far
 * @param fired            whether the join has already fired
 */
public record JoinState(JoinKey key, int expectedBranches, Set<String> arrivedBranches, boolean fired) {

    public JoinState {
        if (key == null) {
            throw new JoinProtocolException("key must be non-null");
        }
        if (expectedBranches < 1) {
            throw new JoinProtocolException("expectedBranches must be at least 1, was " + expectedBranches);
        }
        arrivedBranches = Set.copyOf(arrivedBranches);
        if (arrivedBranches.size() > expectedBranches) {
            throw new JoinProtocolException(
                    "join " + key + " has " + arrivedBranches.size() + " branches, expected " + expectedBranches);
        }
        if (fired && arrivedBranches.size() != expectedBranches) {
            throw new JoinProtocolException("join " + key + " cannot have fired before all branches arrived");
        }
    }

    /** A join no branch has reached yet. */
    public static JoinState open(JoinKey key, int expectedBranches) {
        return new JoinState(key, expectedBranches, Set.of(), false);
    }

    public boolean isComplete() {
        return arrivedBranches.size() == expectedBranches;
    }

    /** This state with {@code branchId} added; throws if that would exceed the expected count. */
    JoinState withArrival(String branchId) {
        var arrived = new HashSet<>(arrivedBranches);
        arrived.add(branchId);
        return new JoinState(key, expectedBranches, arrived, fired);
    }

    JoinState markFired() {
        return new JoinState(key, expectedBranches, arrivedBranches, true);
    }
}
