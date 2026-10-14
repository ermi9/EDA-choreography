package com.eda.choreography.domain.plan;

/**
 * One step of a plan, run by a service. A service may run several steps of one flow; the
 * message names the step it is for.
 *
 * @param id      the step, unique in its flow
 * @param service the logical name of the service that runs it, resolved through discovery
 * @param kind  a plain task, or a join that waits for every branch of its fork
 * @param split how the instance leaves the step: along its one edge, along every edge (a fork),
 *              or along the first edge whose condition holds (a choice)
 */
public record PlanNode(String id, String service, Kind kind, Split split) {

    public enum Kind { TASK, JOIN }

    public enum Split { NONE, PARALLEL, EXCLUSIVE }

    public PlanNode {
        if (id == null || id.isBlank()) {
            throw new InvalidPlanException("a step needs a non-blank id");
        }
        if (service == null || service.isBlank()) {
            throw new InvalidPlanException("step " + id + " needs a non-blank service");
        }
        if (kind == null || split == null) {
            throw new InvalidPlanException("step " + id + " needs a kind and a split");
        }
    }
}
