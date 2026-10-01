package com.eda.choreography.domain.join;

/**
 * One branch reaching a join.
 *
 * <p>Every arrival carries {@code expectedBranches} (taken from the plan) because there is no
 * coordinator to announce it: whichever branch arrives first opens the join.
 *
 * @param key              the join being reached
 * @param branchId         the branch that completed; the id of its last trace entry
 * @param expectedBranches how many distinct branches the join waits for
 */
public record BranchArrival(JoinKey key, String branchId, int expectedBranches) {

    public BranchArrival {
        if (key == null) {
            throw new JoinProtocolException("key must be non-null");
        }
        JoinProtocolException.requireText(branchId, "branchId");
        if (expectedBranches < 1) {
            throw new JoinProtocolException("expectedBranches must be at least 1, was " + expectedBranches);
        }
    }
}
