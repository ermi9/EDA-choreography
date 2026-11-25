package com.eda.choreography.domain.step;

/**
 * A step's work may have happened: its service never answered, or it did the work and the result
 * could not be handed on. The step is tried again under the same run, and if it is given up on it
 * must be undone (see {@link StepRunner#failUncertain}).
 */
public class UncertainOutcomeException extends RuntimeException {

    public UncertainOutcomeException(String message) {
        super(message);
    }

    public UncertainOutcomeException(String message, Throwable cause) {
        super(message, cause);
    }
}
