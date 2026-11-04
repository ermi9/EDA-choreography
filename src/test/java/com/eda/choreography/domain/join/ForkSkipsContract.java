package com.eda.choreography.domain.join;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What every {@link ForkSkips} must do; each implementation's test implements this interface. */
public interface ForkSkipsContract {

    JoinKey FORK = new JoinKey("corr-1", "J");

    ForkSkips skips();

    @Test
    default void aStepOfAForkNoBranchFailedInRuns() {
        assertThat(skips().skips(FORK, "B:1")).isFalse();
    }

    @Test
    default void aStepOfAFailedForkIsSkipped() {
        skips().markFailed(FORK);

        assertThat(skips().skips(FORK, "B:1")).isTrue();
    }

    @Test
    default void markingAForkFailedTwiceChangesNothing() {
        skips().markFailed(FORK);
        skips().markFailed(FORK);

        assertThat(skips().skips(FORK, "B:1")).isTrue();
    }

    @Test
    default void aStepThatWasToldToRunIsToldSoAgainAfterTheForkFails() {
        // Its first run may have done the work before the message was redelivered.
        assertThat(skips().skips(FORK, "B:1")).isFalse();

        skips().markFailed(FORK);

        assertThat(skips().skips(FORK, "B:1")).isFalse();
        assertThat(skips().skips(FORK, "C:1")).isTrue();
    }

    @Test
    default void aStepThatWasSkippedStaysSkipped() {
        skips().markFailed(FORK);
        assertThat(skips().skips(FORK, "B:1")).isTrue();

        assertThat(skips().skips(FORK, "B:1")).isTrue();
    }

    @Test
    default void aFailureOnlyConcernsItsOwnForkAndInstance() {
        skips().markFailed(FORK);

        assertThat(skips().skips(new JoinKey("corr-2", "J"), "B:2")).isFalse();
        assertThat(skips().skips(new JoinKey("corr-1", "K"), "B:3")).isFalse();
    }
}
