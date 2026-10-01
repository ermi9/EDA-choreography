package com.eda.choreography.domain.join;

/**
 * An arrival that no correct execution of the plan can produce: more distinct branches than
 * expected, or branches disagreeing on how many are expected.
 */
public class JoinProtocolException extends IllegalArgumentException {

    public JoinProtocolException(String message) {
        super(message);
    }

    static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new JoinProtocolException(name + " must be non-blank");
        }
    }
}
