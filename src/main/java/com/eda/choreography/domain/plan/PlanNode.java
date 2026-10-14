package com.eda.choreography.domain.plan;

/**
 * One step of a plan. Its id is the logical name of the service that runs it, so a service
 * runs at most one step of a flow.
 *
 * @param id    the step, and the service that runs it
 * @param kind  a plain task, or a join that waits for every branch of its fork
 * @param split how the instance leaves the step: along its one edge, along every edge (a fork),
 *              or along the first edge whose condition holds (a choice)
 */
public record PlanNode(String id, Kind kind, Split split) {

    public enum Kind { TASK, JOIN }

    public enum Split { NONE, PARALLEL, EXCLUSIVE }

    public PlanNode {
        if (id == null || id.isBlank()) {
            throw new InvalidPlanException("a step needs a non-blank id");
        }
        if (kind == null || split == null) {
            throw new InvalidPlanException("step " + id + " needs a kind and a split");
        }
    }
}
