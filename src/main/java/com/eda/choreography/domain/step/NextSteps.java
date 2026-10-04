package com.eda.choreography.domain.step;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.List;

/**
 * Decides where an instance goes after a step, from the step's own view of the message (which
 * already includes the step's result).
 */
@FunctionalInterface
public interface NextSteps {

    /** The logical names of the steps to hand the message to; empty when the flow is complete. */
    List<String> after(String stepId, ChoreographyMessage message);
}
