package com.eda.choreography.domain.step;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.List;

/**
 * Test fixture: a hardcoded linear flow, the routing INC-3's walking skeleton runs on. INC-5
 * replaces it with routing derived from a {@code PlanModel}.
 */
public record LinearFlow(String flowName, List<String> steps) implements NextSteps {

    public LinearFlow {
        steps = List.copyOf(steps);
    }

    public static LinearFlow of(String flowName, String... steps) {
        return new LinearFlow(flowName, List.of(steps));
    }

    public String first() {
        return steps.get(0);
    }

    @Override
    public List<String> after(String stepId, ChoreographyMessage message) {
        if (!message.flowName().equals(flowName)) {
            throw new IllegalArgumentException("flow " + flowName + " cannot route a message of " + message.flowName());
        }
        int position = steps.indexOf(stepId);
        if (position < 0) {
            throw new IllegalArgumentException("step " + stepId + " is not part of flow " + flowName);
        }
        return position + 1 < steps.size() ? List.of(steps.get(position + 1)) : List.of();
    }
}
