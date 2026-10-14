package com.eda.choreography.domain.plan;

/**
 * Produces the plan of a flow from some description of it. A drawn BPMN diagram is one such
 * description; another could be text, without the runtime changing, because the runtime only
 * ever reads a {@link PlanModel}.
 *
 * @param <S> what the flow is described in
 */
@FunctionalInterface
public interface Planner<S> {

    /**
     * The plan the description draws.
     *
     * @throws InvalidPlanException if the description cannot become a plan that runs
     */
    PlanModel plan(S description);
}
