package com.eda.choreography.domain.plan;

/**
 * An edge from one step to the next. Only the edges leaving a choice carry a condition, except
 * for at most one default edge, taken when no condition holds. A condition is kept as written
 * and evaluated at runtime, on the result of the step it leaves.
 *
 * @param from      the step the edge leaves
 * @param to        the step it leads to
 * @param condition the condition, or {@code null} for an unconditional or default edge
 * @param isDefault whether this is a choice's default edge
 */
public record PlanEdge(String from, String to, String condition, boolean isDefault) {

    public PlanEdge {
        if (from == null || to == null) {
            throw new InvalidPlanException("an edge needs both ends");
        }
        if (condition != null && condition.isBlank()) {
            throw new InvalidPlanException("the edge " + from + " -> " + to + " has a blank condition");
        }
        if (condition != null && isDefault) {
            throw new InvalidPlanException("the default edge " + from + " -> " + to + " cannot carry a condition");
        }
    }

    public boolean isConditional() {
        return condition != null;
    }
}
