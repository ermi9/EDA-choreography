package com.eda.choreography.domain.plan;

import java.util.Map;

/**
 * Evaluates a choice's condition on the result of the step the choice leaves. The language is
 * the adapter's business; the plan only carries the text.
 */
@FunctionalInterface
public interface Conditions {

    /**
     * Whether the condition holds for the result.
     *
     * @throws RuntimeException if the condition cannot be evaluated, or is not true or false
     */
    boolean holds(String condition, Map<String, Object> result);
}
