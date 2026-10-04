package com.eda.choreography.domain.step;

import com.eda.choreography.domain.message.ChoreographyMessage;

/**
 * Hands a message on. The domain addresses steps by logical name; turning a name into a topic
 * (or anything else) is the adapter's business.
 *
 * <p>Both methods return only once the message is accepted, so a consumer that acknowledges its
 * input after {@link StepRunner#handle} never loses a hop.
 */
public interface MessagePublisher {

    void publish(String stepId, ChoreographyMessage message);

    /** Reports that the instance has run its last step. */
    void publishCompleted(ChoreographyMessage message);
}
