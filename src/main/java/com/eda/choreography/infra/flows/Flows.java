package com.eda.choreography.infra.flows;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.plan.PlanModel;
import com.eda.choreography.domain.plan.Plans;
import com.eda.choreography.domain.plan.ServiceBindings;
import com.eda.choreography.domain.resolution.ResolutionSource;
import com.eda.choreography.domain.step.MessagePublisher;
import com.eda.choreography.infra.bpmn.BpmnFlow;
import com.eda.choreography.infra.bpmn.BpmnPlanner;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Defining and starting flows: the authoring pipeline's two entry points. A flow is translated
 * when it is defined, so one that cannot run is rejected then and never stored. Starting a flow
 * hands its first step the request, as the instance's input, and returns the instance's
 * correlation id; from there the services choreograph it among themselves.
 */
public class Flows {

    /** Flow names appear in URLs and file names. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final FlowStore store;
    private final Plans plans;
    private final BpmnPlanner planner;
    private final ResolutionSource catalog;
    private final MessagePublisher publisher;

    public Flows(FlowStore store, Plans plans, BpmnPlanner planner, ResolutionSource catalog, MessagePublisher publisher) {
        this.store = Objects.requireNonNull(store, "store");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    /**
     * Translates and stores a drawn flow.
     *
     * @return which of its services discovery knows and which are bound late
     * @throws com.eda.choreography.domain.plan.InvalidPlanException if it cannot run as drawn
     */
    public Defined define(String name, String bpmn) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("a flow name may only hold letters, digits, '.', '_' and '-'");
        }
        var plan = planner.plan(new BpmnFlow(name, bpmn));
        store.save(name, bpmn);
        return new Defined(plan, ServiceBindings.of(plan, catalog));
    }

    /**
     * Starts an instance of a stored flow.
     *
     * @throws UnknownFlowException if no flow of that name is stored
     */
    public String start(String name, Map<String, ?> input) {
        var plan = plans.forFlow(name).orElseThrow(() -> new UnknownFlowException(name));
        var correlationId = name + "-" + UUID.randomUUID();
        var first = plan.start();
        publisher.publish(plan.serviceOf(first),
                ChoreographyMessage.start(correlationId, name, input).addressedTo(first));
        return correlationId;
    }

    public record Defined(PlanModel plan, ServiceBindings bindings) {}

    public static class UnknownFlowException extends RuntimeException {

        public UnknownFlowException(String name) {
            super("there is no flow named " + name);
        }
    }
}
