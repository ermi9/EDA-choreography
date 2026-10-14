package com.eda.choreography.domain.step;

import java.time.Duration;

/**
 * How a join waits in one flow.
 *
 * @param expectedBranches how many branches it waits for
 * @param timeout          how long it waits for the last one, counted from the first
 */
public record JoinWait(int expectedBranches, Duration timeout) {

    public JoinWait {
        if (expectedBranches < 1) {
            throw new IllegalArgumentException("expectedBranches must be at least 1, was " + expectedBranches);
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, was " + timeout);
        }
    }
}
