package com.eda.choreography.domain.join;

import java.util.Optional;

/**
 * Where join state lives between arrivals.
 *
 * <p>The store only keeps state; it makes no join decisions. That is what lets INC-4 swap in a
 * Redis implementation with {@link JoinStateMachine} unchanged.
 *
 * <p>Implementations are not required to make a {@code find} followed by a {@code save} atomic.
 * Callers must deliver the arrivals of one correlation id serially (the runtime does this by
 * partitioning on the correlation id).
 */
public interface JoinStateStore {

    Optional<JoinState> find(JoinKey key);

    void save(JoinState state);
}
