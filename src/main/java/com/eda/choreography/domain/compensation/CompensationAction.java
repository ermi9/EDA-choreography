package com.eda.choreography.domain.compensation;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.trace.TraceEntry;

/**
 * A service's own way of undoing one of its steps. It gets the entry to undo and the instance,
 * whose {@code results} hold what the step produced.
 *
 * <p>It must be idempotent: delivery is at-least-once, so the same entry can be undone twice.
 */
@FunctionalInterface
public interface CompensationAction {

    void undo(TraceEntry entry, ChoreographyMessage instance);
}
