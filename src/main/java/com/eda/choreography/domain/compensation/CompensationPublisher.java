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

    /**
     * Parks a request whose entry could not be undone, for an operator. The request holds
     * everything needed to retry it: once the cause is fixed, it is sent to the step again.
     */
    void publishFailed(CompensationRequest request);
}
