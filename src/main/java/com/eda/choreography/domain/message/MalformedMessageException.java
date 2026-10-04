package com.eda.choreography.domain.message;

/** A message no correct execution can produce: blank identity, a foreign trace, or results nothing refers to. */
public class MalformedMessageException extends IllegalArgumentException {

    public MalformedMessageException(String message) {
        super(message);
    }
}
