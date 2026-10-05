package com.eda.choreography.infra.redis;

import com.eda.choreography.domain.join.JoinKey;
import com.eda.choreography.domain.join.JoinState;
import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.Map;
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
        boolean fired,
        Map<String, ChoreographyMessage> branchMessages) {

    static StoredJoinState from(JoinState state) {
        return new StoredJoinState(
                state.key().correlationId(),
                state.key().joinId(),
                state.expectedBranches(),
                state.arrivedBranches(),
                state.fired(),
                state.branchMessages());
    }

    /** A join stored before branch messages were kept reads back as one that kept none. */
    JoinState toDomain() {
        var messages = branchMessages == null ? Map.<String, ChoreographyMessage>of() : branchMessages;
        var status = fired ? JoinState.Status.FIRED : JoinState.Status.OPEN;
        return new JoinState(new JoinKey(correlationId, joinId), expectedBranches, arrivedBranches, status, messages);
    }
}
