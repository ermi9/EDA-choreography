package com.eda.choreography.domain.join;

/**
 * Identifies one join of one choreography instance.
 *
 * <p>The {@code correlationId} alone is not enough: one instance can pass through more than one
 * fork/join section in sequence, and each join accumulates its own branches.
 *
 * @param correlationId the choreography instance
 * @param joinId        the plan step that joins the branches
 */
public record JoinKey(String correlationId, String joinId) {

    public JoinKey {
        JoinProtocolException.requireText(correlationId, "correlationId");
        JoinProtocolException.requireText(joinId, "joinId");
    }
}
