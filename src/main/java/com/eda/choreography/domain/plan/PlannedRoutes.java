package com.eda.choreography.domain.plan;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.step.NextSteps;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Routing read from the plan of the flow a message runs: content-based, decided by each step
 * on its own result, with no coordinator. A step with one edge out hands on along it, a fork
 * along every edge, and a choice along the first edge, in the order drawn, whose condition holds
 * for the step's own result. If none holds, the choice takes its default edge.
 *
 * <p>A choice looks only at the result of the step it leaves, never at earlier steps' results.
 * Every input to its decision is then local to the service deciding.
 *
 * <p>When no route exists, a {@link NoRouteException} is thrown and the step fails.
 */
public final class PlannedRoutes implements NextSteps {

    private final Plans plans;
    private final Conditions conditions;

    public PlannedRoutes(Plans plans, Conditions conditions) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.conditions = Objects.requireNonNull(conditions, "conditions");
    }

    @Override
    public List<String> after(String stepId, ChoreographyMessage message) {
        var plan = planOf(message);
        var node = plan.node(stepId).orElseThrow(() ->
                new NoRouteException("step " + stepId + " is not part of flow " + plan.flowName()));
        var edges = plan.outgoing(stepId);
        if (node.split() != PlanNode.Split.EXCLUSIVE) {
            return edges.stream().map(PlanEdge::to).toList();
        }
        var result = ownResult(stepId, message);
        return edges.stream()
                .filter(PlanEdge::isConditional)
                .filter(edge -> conditions.holds(edge.condition(), result))
                .findFirst()
                .or(() -> edges.stream().filter(PlanEdge::isDefault).findFirst())
                .map(edge -> List.of(edge.to()))
                .orElseThrow(() -> new NoRouteException("no condition of choice " + stepId + " in flow "
                        + plan.flowName() + " holds for " + result + ", and it has no default"));
    }

    @Override
    public Optional<String> enclosingJoin(String stepId, ChoreographyMessage message) {
        return planOf(message).enclosingJoin(stepId);
    }

    /** Planned routing differs by flow, so it cannot answer without the message. */
    @Override
    public Optional<String> enclosingJoin(String stepId) {
        throw new UnsupportedOperationException("planned routing needs the message to know its flow");
    }

    private PlanModel planOf(ChoreographyMessage message) {
        return plans.forFlow(message.flowName()).orElseThrow(() ->
                new NoRouteException("there is no plan for flow " + message.flowName()));
    }

    /** The step has just been recorded, so it is the last entry of the trace. */
    private static Map<String, Object> ownResult(String stepId, ChoreographyMessage message) {
        var trace = message.trace();
        var last = trace.isEmpty() ? null : trace.get(trace.size() - 1);
        if (last == null || !last.stepId().equals(stepId) || last.resultRef() == null) {
            throw new IllegalArgumentException("the message does not end with a result of " + stepId);
        }
        return message.results().get(last.resultRef());
    }
}
