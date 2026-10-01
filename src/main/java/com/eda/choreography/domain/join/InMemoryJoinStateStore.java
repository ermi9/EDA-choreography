package com.eda.choreography.domain.join;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link JoinStateStore} held in process memory. State is lost on restart; INC-4 replaces it
 * with a Redis-backed store for durability.
 */
public final class InMemoryJoinStateStore implements JoinStateStore {

    private final Map<JoinKey, JoinState> states = new ConcurrentHashMap<>();

    @Override
    public Optional<JoinState> find(JoinKey key) {
        return Optional.ofNullable(states.get(key));
    }

    @Override
    public void save(JoinState state) {
        states.put(state.key(), state);
    }
}
