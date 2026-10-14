package com.eda.choreography.domain.plan;

/**
 * A step cannot tell where its instance goes next: its flow or the step is not in any plan, or
 * none of a choice's conditions held and the choice has no default. The step fails like any
 * step that throws, and its instance is undone.
 */
public class NoRouteException extends RuntimeException {

    public NoRouteException(String message) {
        super(message);
    }
}
