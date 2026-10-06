package com.eda.choreography.infra.kafka;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.step.NextSteps;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test fixture: routing written out by hand, step by step, with the join each fork branch ends
 * at. INC-5 derives the same answers from a {@code PlanModel}.
 */
final class Routes implements NextSteps {

    private final Map<String, List<String>> next = new HashMap<>();
    private final Map<String, String> joins = new HashMap<>();

    Routes then(String stepId, String... nextSteps) {
        next.put(stepId, List.of(nextSteps));
        return this;
    }

    /** {@code branchSteps} all run between a fork and {@code join}. */
    Routes joinedAt(String join, String... branchSteps) {
        for (var step : branchSteps) {
            joins.put(step, join);
        }
        return this;
    }

    @Override
    public List<String> after(String stepId, ChoreographyMessage message) {
        return next.getOrDefault(stepId, List.of());
    }

    @Override
    public Optional<String> enclosingJoin(String stepId) {
        return Optional.ofNullable(joins.get(stepId));
    }
}
