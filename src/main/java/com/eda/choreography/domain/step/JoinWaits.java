package com.eda.choreography.domain.step;

import java.time.Duration;

/**
 * How a join waits, by flow. One join service can close forks of several flows, each with its
 * own number of branches and its own timeout, so the join asks with the flow of each branch.
 */
@FunctionalInterface
public interface JoinWaits {

    JoinWait forJoin(String flowName, String joinStep);

    /** The same wait in every flow. */
    static JoinWaits fixed(int expectedBranches, Duration timeout) {
        var wait = new JoinWait(expectedBranches, timeout);
        return (flowName, joinStep) -> wait;
    }
}
