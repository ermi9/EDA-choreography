package com.eda.choreography.domain.join;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The accumulated state of one join.
 *
 * <p>Arrivals are held as a <em>set</em> of branch ids, not a counter: delivery is
 * at-least-once, so the same branch can arrive twice, and a counter would count it twice and
 * fire before the other branches are done. The set makes a repeat a no-op.
 *
 * <p>The state is kept after the join fires so that a late duplicate is recognised as a
 * duplicate instead of opening a fresh join that could never complete.
 *
 * @param key              the join
 * @param expectedBranches how many distinct branches complete the join
 * @param arrivedBranches  the distinct branch ids seen so far
 * @param status           whether the join is still waiting, has fired, or gave up
 * @param branchMessages   the message each branch arrived with, for the branches that brought
 *                         one; the join merges them when it fires
 */
public record JoinState(
        JoinKey key,
        int expectedBranches,
        Set<String> arrivedBranches,
        Status status,
        Map<String, ChoreographyMessage> branchMessages) {

    public enum Status {
        /** Waiting for branches. */
        OPEN,
        /** Every branch arrived and the join fired. */
        FIRED,
        /** The join gave up waiting; it will not fire. */
        TIMED_OUT
    }

    public JoinState {
        if (key == null) {
            throw new JoinProtocolException("key must be non-null");
        }
        if (expectedBranches < 1) {
            throw new JoinProtocolException("expectedBranches must be at least 1, was " + expectedBranches);
        }
        if (status == null) {
            throw new JoinProtocolException("status must be non-null");
        }
        arrivedBranches = Set.copyOf(arrivedBranches);
        branchMessages = Map.copyOf(branchMessages);
        if (arrivedBranches.size() > expectedBranches) {
            throw new JoinProtocolException(
                    "join " + key + " has " + arrivedBranches.size() + " branches, expected " + expectedBranches);
        }
        if (status == Status.FIRED && arrivedBranches.size() != expectedBranches) {
            throw new JoinProtocolException("join " + key + " cannot have fired before all branches arrived");
        }
        for (var branch : branchMessages.entrySet()) {
            if (!arrivedBranches.contains(branch.getKey())) {
                throw new JoinProtocolException("join " + key + " keeps a message for branch " + branch.getKey()
                        + ", which has not arrived");
            }
            if (!branch.getValue().correlationId().equals(key.correlationId())) {
                throw new JoinProtocolException("join " + key + " was handed a message of "
                        + branch.getValue().correlationId());
            }
        }
    }

    /** A state that only counts branches and keeps no messages. */
    public JoinState(JoinKey key, int expectedBranches, Set<String> arrivedBranches, boolean fired) {
        this(key, expectedBranches, arrivedBranches, fired ? Status.FIRED : Status.OPEN, Map.of());
    }

    /** A join no branch has reached yet. */
    public static JoinState open(JoinKey key, int expectedBranches) {
        return new JoinState(key, expectedBranches, Set.of(), false);
    }

    public boolean fired() {
        return status == Status.FIRED;
    }

    public boolean isComplete() {
        return arrivedBranches.size() == expectedBranches;
    }

    /** This state with {@code branchId} added; throws if that would exceed the expected count. */
    JoinState withArrival(String branchId) {
        return withArrival(branchId, null);
    }

    /** As {@link #withArrival(String)}, also keeping the branch's message when there is one. */
    JoinState withArrival(String branchId, ChoreographyMessage message) {
        var arrived = new HashSet<>(arrivedBranches);
        arrived.add(branchId);
        var messages = new HashMap<>(branchMessages);
        if (message != null) {
            messages.putIfAbsent(branchId, message);
        }
        return new JoinState(key, expectedBranches, arrived, status, messages);
    }

    JoinState markTimedOut() {
        return new JoinState(key, expectedBranches, arrivedBranches, Status.TIMED_OUT, branchMessages);
    }

    JoinState markFired() {
        return new JoinState(key, expectedBranches, arrivedBranches, Status.FIRED, branchMessages);
    }
}
