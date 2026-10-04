package com.eda.choreography.domain.step;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.ArrayList;
import java.util.List;

/** Test double: keeps what a {@link StepRunner} publishes instead of sending it anywhere. */
class RecordingPublisher implements MessagePublisher {

    record Sent(String stepId, ChoreographyMessage message) {}

    final List<Sent> sent = new ArrayList<>();
    final List<ChoreographyMessage> completed = new ArrayList<>();

    @Override
    public void publish(String stepId, ChoreographyMessage message) {
        sent.add(new Sent(stepId, message));
    }

    @Override
    public void publishCompleted(ChoreographyMessage message) {
        completed.add(message);
    }
}
