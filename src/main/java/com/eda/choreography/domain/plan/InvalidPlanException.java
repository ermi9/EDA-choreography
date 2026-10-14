package com.eda.choreography.domain.plan;

/** A plan that cannot run as drawn: it is rejected as a whole, before any instance starts. */
public class InvalidPlanException extends RuntimeException {

    public InvalidPlanException(String message) {
        super(message);
    }
}
