package com.eda.choreography.domain.step;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.List;
import java.util.Optional;

/**
 * Decides where an instance goes after a step, from the step's own view of the message (which
 * already includes the step's result).
 */
@FunctionalInterface
public interface NextSteps {

    /** The logical names of the steps to hand the message to; empty when the flow is complete. */
    List<String> after(String stepId, ChoreographyMessage message);

    /**
     * The join that the branch running {@code stepId} ends at, if the step runs between a fork
     * and its join. A step there that fails reports to the join instead of compensating, so the
     * join learns about every branch before the instance is undone.
     */
    default Optional<String> enclosingJoin(String stepId) {
        return Optional.empty();
    }
}
