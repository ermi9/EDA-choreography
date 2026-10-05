package com.eda.choreography.infra.redis;

import com.eda.choreography.domain.join.JoinKey;
import com.eda.choreography.domain.join.JoinState;
import java.util.Set;

/**
 * How a {@link JoinState} is kept in Redis, as JSON. A separate record so the stored form is
 * written down in one place and does not change because a domain record gains a method.
 */
record StoredJoinState(
        String correlationId,
        String joinId,
        int expectedBranches,
        Set<String> arrivedBranches,
        boolean fired) {

    static StoredJoinState from(JoinState state) {
        return new StoredJoinState(
                state.key().correlationId(),
                state.key().joinId(),
                state.expectedBranches(),
                state.arrivedBranches(),
                state.fired());
    }

    JoinState toDomain() {
        return new JoinState(new JoinKey(correlationId, joinId), expectedBranches, arrivedBranches, fired);
    }
}
