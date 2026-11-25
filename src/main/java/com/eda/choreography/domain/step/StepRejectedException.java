package com.eda.choreography.domain.step;

/**
 * A step's service refused the work for good and committed nothing, such as an order for more
 * than is in stock. Trying again would give the same answer, so the step fails at once.
 */
public class StepRejectedException extends RuntimeException {

    public StepRejectedException(String message) {
        super(message);
    }
}
