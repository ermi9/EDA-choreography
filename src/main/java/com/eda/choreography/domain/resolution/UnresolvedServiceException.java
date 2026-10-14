package com.eda.choreography.domain.resolution;

/** A service could not be resolved to a destination: discovery does not know it, or it may not take work now. */
public class UnresolvedServiceException extends RuntimeException {

    public UnresolvedServiceException(String message) {
        super(message);
    }
}
