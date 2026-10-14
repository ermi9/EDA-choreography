package com.eda.choreography.infra.flows;

import com.eda.choreography.domain.plan.PlanModel;
import com.eda.choreography.domain.plan.Plans;
import com.eda.choreography.infra.bpmn.BpmnFlow;
import com.eda.choreography.infra.bpmn.BpmnPlanner;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The plans of the stored flows. A flow is read from the store on every lookup, so an upload
 * reaches every process at once, but it is translated only when its BPMN changed.
 */
public class StoredPlans implements Plans {

    private final FlowStore store;
    private final BpmnPlanner planner;
    private final Map<String, Translated> translated = new ConcurrentHashMap<>();

    public StoredPlans(FlowStore store, BpmnPlanner planner) {
        this.store = Objects.requireNonNull(store, "store");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    @Override
    public Optional<PlanModel> forFlow(String flowName) {
        return store.find(flowName).map(bpmn -> translated.compute(flowName, (name, known) ->
                known != null && known.bpmn().equals(bpmn)
                        ? known
                        : new Translated(bpmn, planner.plan(new BpmnFlow(name, bpmn)))).plan());
    }

    private record Translated(String bpmn, PlanModel plan) {}
}
