package com.eda.choreography.domain.step;

import com.eda.choreography.domain.compensation.CompensationPublisher;
import com.eda.choreography.domain.compensation.CompensationRequest;
import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.ArrayList;
import java.util.List;

/** Test double: keeps what a compensation trigger sends instead of sending it anywhere. */
class RecordingCompensations implements CompensationPublisher {

    record Sent(String stepId, CompensationRequest request) {}

    final List<Sent> requests = new ArrayList<>();
    final List<ChoreographyMessage> compensated = new ArrayList<>();
    final List<CompensationRequest> failed = new ArrayList<>();

    @Override
    public void publish(String stepId, CompensationRequest request) {
        requests.add(new Sent(stepId, request));
    }

    @Override
    public void publishCompensated(ChoreographyMessage instance) {
        compensated.add(instance);
    }

    @Override
    public void publishFailed(CompensationRequest request) {
        failed.add(request);
    }
}
