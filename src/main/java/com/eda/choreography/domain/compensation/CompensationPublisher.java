package com.eda.choreography.domain.compensation;

import com.eda.choreography.domain.message.ChoreographyMessage;

/**
 * Hands compensation requests on. Like the forward publisher, it addresses services by logical
 * name and returns only once the message is accepted.
 */
public interface CompensationPublisher {

    void publish(String stepId, CompensationRequest request);

    /** Reports that every completed step of the instance has been undone. */
    void publishCompensated(ChoreographyMessage instance);
}
