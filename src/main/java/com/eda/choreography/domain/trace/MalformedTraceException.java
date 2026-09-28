package com.eda.choreography.domain.trace;

/** A trace that cannot be a valid execution DAG: dangling parent, cycle, conflicting ids, mixed instances. */
public class MalformedTraceException extends IllegalArgumentException {

    public MalformedTraceException(String message) {
        super(message);
    }
}
