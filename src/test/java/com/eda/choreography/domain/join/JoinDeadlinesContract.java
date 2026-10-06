package com.eda.choreography.domain.join;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** What every {@link JoinDeadlines} must do; each implementation's test implements this interface. */
public interface JoinDeadlinesContract {

    Instant NOON = Instant.parse("2026-10-06T12:00:00Z");
    JoinKey JOIN = new JoinKey("corr-1", "J");

    JoinDeadlines deadlines();

    @Test
    default void aJoinIsNotDueBeforeItsDeadline() {
        deadlines().setIfAbsent(JOIN, NOON);

        assertThat(deadlines().dueBy(NOON.minusMillis(1), 10)).isEmpty();
        assertThat(deadlines().dueBy(NOON, 10)).containsExactly(JOIN);
    }

    @Test
    default void aLaterBranchDoesNotExtendTheDeadline() {
        deadlines().setIfAbsent(JOIN, NOON);
        deadlines().setIfAbsent(JOIN, NOON.plusSeconds(60));

        assertThat(deadlines().dueBy(NOON, 10)).containsExactly(JOIN);
    }

    @Test
    default void returnsTheEarliestDueJoinsFirstAndNoMoreThanAsked() {
        var later = new JoinKey("corr-2", "J");
        var earliest = new JoinKey("corr-3", "J");
        deadlines().setIfAbsent(JOIN, NOON.minusSeconds(10));
        deadlines().setIfAbsent(later, NOON);
        deadlines().setIfAbsent(earliest, NOON.minusSeconds(20));

        assertThat(deadlines().dueBy(NOON, 2)).containsExactly(earliest, JOIN);
    }

    @Test
    default void aRemovedJoinIsNoLongerDue() {
        deadlines().setIfAbsent(JOIN, NOON);

        deadlines().remove(JOIN);
        deadlines().remove(JOIN);

        assertThat(deadlines().dueBy(NOON, 10)).isEmpty();
    }

    @Test
    default void givesBackKeysWhoseIdsContainTheSeparator() {
        var awkward = new JoinKey("corr:1", "1:J");
        deadlines().setIfAbsent(awkward, NOON);

        assertThat(deadlines().dueBy(NOON, 10)).containsExactly(awkward);
    }
}
