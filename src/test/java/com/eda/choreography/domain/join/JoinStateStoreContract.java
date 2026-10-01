package com.eda.choreography.domain.join;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What every {@link JoinStateStore} must do. Each implementation's test extends this class, so
 * the in-memory store and INC-4's Redis store are held to one contract.
 */
abstract class JoinStateStoreContract {

    protected abstract JoinStateStore store();

    private static final JoinKey JOIN = new JoinKey("corr-1", "J");

    @Test
    void findsNothingForAnUnknownJoin() {
        assertThat(store().find(JOIN)).isEmpty();
    }

    @Test
    void returnsWhatWasSaved() {
        var state = new JoinState(JOIN, 3, Set.of("B", "C"), false);

        store().save(state);

        assertThat(store().find(JOIN)).contains(state);
    }

    @Test
    void laterSaveReplacesEarlierOne() {
        store().save(new JoinState(JOIN, 2, Set.of("B"), false));
        var fired = new JoinState(JOIN, 2, Set.of("B", "C"), true);

        store().save(fired);

        assertThat(store().find(JOIN)).contains(fired);
    }

    @Test
    void keepsJoinsApartByCorrelationAndJoinId() {
        var otherInstance = new JoinKey("corr-2", "J");
        var otherJoin = new JoinKey("corr-1", "K");
        store().save(new JoinState(JOIN, 2, Set.of("B"), false));

        assertThat(store().find(otherInstance)).isEmpty();
        assertThat(store().find(otherJoin)).isEmpty();
    }
}
