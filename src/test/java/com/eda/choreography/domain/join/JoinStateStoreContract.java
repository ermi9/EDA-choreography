package com.eda.choreography.domain.join;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What every {@link JoinStateStore} must do. Each implementation's test implements this
 * interface, so the in-memory store and the Redis store are held to one contract. It is an
 * interface so that a container-backed test can still extend its own base class.
 */
public interface JoinStateStoreContract {

    JoinKey JOIN = new JoinKey("corr-1", "J");

    JoinStateStore store();

    @Test
    default void findsNothingForAnUnknownJoin() {
        assertThat(store().find(JOIN)).isEmpty();
    }

    @Test
    default void returnsWhatWasSaved() {
        var state = new JoinState(JOIN, 3, Set.of("B", "C"), false);

        store().save(state);

        assertThat(store().find(JOIN)).contains(state);
    }

    @Test
    default void laterSaveReplacesEarlierOne() {
        store().save(new JoinState(JOIN, 2, Set.of("B"), false));
        var fired = new JoinState(JOIN, 2, Set.of("B", "C"), true);

        store().save(fired);

        assertThat(store().find(JOIN)).contains(fired);
    }

    @Test
    default void keepsJoinsApartByCorrelationAndJoinId() {
        var otherInstance = new JoinKey("corr-2", "J");
        var otherJoin = new JoinKey("corr-1", "K");
        store().save(new JoinState(JOIN, 2, Set.of("B"), false));

        assertThat(store().find(otherInstance)).isEmpty();
        assertThat(store().find(otherJoin)).isEmpty();
    }

    @Test
    default void keepsJoinsApartWhenTheirIdsOnlyDifferInWhereASeparatorFalls() {
        var left = new JoinKey("corr:1", "J");
        var right = new JoinKey("corr", "1:J");
        store().save(new JoinState(left, 2, Set.of("B"), false));

        assertThat(store().find(right)).isEmpty();
    }
}
