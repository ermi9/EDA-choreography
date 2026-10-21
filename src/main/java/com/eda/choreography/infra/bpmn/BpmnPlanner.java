package com.eda.choreography.infra.bpmn;

import com.eda.choreography.domain.plan.InvalidPlanException;
import com.eda.choreography.domain.plan.PlanModel;
import com.eda.choreography.domain.plan.PlanNode.Split;
import com.eda.choreography.domain.plan.Planner;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent;
import org.camunda.bpm.model.bpmn.instance.CompensateEventDefinition;
import org.camunda.bpm.model.bpmn.instance.EndEvent;
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.FlowElement;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.ParallelGateway;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.bpmn.instance.Task;
import org.camunda.bpm.model.xml.ModelException;

/**
 * Turns a drawn BPMN flow into the plan its instances run. It only reads the diagram, with the
 * standalone {@code camunda-bpmn-model} library; nothing here executes BPMN.
 *
 * <p>What a diagram may hold, and what it becomes:
 * <ul>
 *   <li>one start event, leading to the first task, and any number of end events;</li>
 *   <li>tasks of any kind, each a step run by a service: the {@code choreo:service} attribute
 *       names it, or else the task's name does. A step is named after its service when only one
 *       task runs that service, and after the task's id otherwise;</li>
 *   <li>an exclusive gateway after a task makes that task a choice. Each outgoing flow carries a
 *       condition, passed through as written (it is evaluated at runtime, on the task's result),
 *       except the gateway's default flow. An exclusive gateway with one outgoing flow only merges
 *       paths, and is passed through;</li>
 *   <li>a parallel gateway after a task makes that task a fork, each outgoing flow a branch. A
 *       parallel gateway where the branches meet must lead straight to a task: that task is the
 *       join step, which runs once every branch has arrived. A join step is named after its
 *       service, so its service runs no other task of the flow;</li>
 *   <li>a flow back to an earlier merging gateway, or straight into an earlier task, draws a
 *       loop. It leaves through a choice, and a step in it runs again each time round;</li>
 *   <li>the process attribute {@code choreo:joinTimeout}, an ISO-8601 duration such as
 *       {@code PT30S}, is how long the flow's joins wait for their last branch;</li>
 *   <li>the process attribute {@code choreo:maxIterations}, a whole number, is how many times a
 *       step may run in one instance before it fails;</li>
 *   <li>compensation tasks and the boundary events that point at them are left out: a step is
 *       undone by its own service, through the compensation topic discovery gave it.</li>
 * </ul>
 * Anything else is rejected as an {@link InvalidPlanException}, as is a diagram whose plan cannot
 * run as drawn (see {@link PlanModel}).
 */
public final class BpmnPlanner implements Planner<BpmnFlow> {

    /** The namespace of this engine's attributes on BPMN elements. */
    public static final String NAMESPACE = "http://eda.com/choreography";

    /** Service names become Kafka topic names, so they keep to the characters a topic allows. */
    private static final Pattern SERVICE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    @Override
    public PlanModel plan(BpmnFlow flow) {
        var process = onlyProcess(flow, parse(flow));
        return new Translation(flow.name(), process).plan();
    }

    private static BpmnModelInstance parse(BpmnFlow flow) {
        try {
            return Bpmn.readModelFromStream(new ByteArrayInputStream(flow.bytes()));
        } catch (ModelException e) {
            throw new InvalidPlanException("flow " + flow.name() + " is not valid BPMN: " + e.getMessage());
        }
    }

    private static Process onlyProcess(BpmnFlow flow, BpmnModelInstance model) {
        var processes = model.getModelElementsByType(Process.class);
        if (processes.size() != 1) {
            throw new InvalidPlanException("flow " + flow.name() + " must draw exactly one process, draws " + processes.size());
        }
        return processes.iterator().next();
    }

    /** One diagram on its way to a plan. */
    private static final class Translation {

        private final String flowName;
        private final Process process;
        private final List<Task> tasks = new ArrayList<>();
        private final Map<Task, String> stepIds = new HashMap<>();
        private final Map<Task, Split> splits = new LinkedHashMap<>();
        private final List<String[]> edges = new ArrayList<>();
        private final Set<Task> joins = new HashSet<>();
        private StartEvent start;

        Translation(String flowName, Process process) {
            this.flowName = flowName;
            this.process = process;
        }

        PlanModel plan() {
            sortElements();
            nameSteps();
            for (var task : tasks) {
                followFrom(task);
            }
            var plan = PlanModel.builder(flowName);
            for (var task : tasks) {
                var split = splits.getOrDefault(task, Split.NONE);
                if (!joins.contains(task)) {
                    plan.task(stepIds.get(task), serviceOf(task), split);
                } else if (stepIds.get(task).equals(serviceOf(task))) {
                    plan.join(stepIds.get(task), split);
                } else {
                    throw invalid("task " + task.getId() + " joins branches, so its service " + serviceOf(task)
                            + " may run no other task of the flow");
                }
            }
            joinTimeout().ifPresent(plan::joinTimeout);
            maxIterations().ifPresent(plan::maxRuns);
            for (var edge : edges) {
                switch (edge[2]) {
                    case "plain" -> plan.edge(edge[0], edge[1]);
                    case "default" -> plan.defaultEdge(edge[0], edge[1]);
                    default -> plan.edge(edge[0], edge[1], edge[3]);
                }
            }
            var built = plan.build();
            var first = firstTask();
            if (!built.start().equals(stepIds.get(first))) {
                throw invalid("its start event leads to " + first.getId() + ", but the plan starts at " + built.start());
            }
            return built;
        }

        private void sortElements() {
            for (FlowElement element : process.getFlowElements()) {
                if (element instanceof Task task) {
                    if (!task.isForCompensation()) {
                        tasks.add(task);
                    }
                } else if (element instanceof StartEvent event) {
                    if (start != null) {
                        throw invalid("it has more than one start event");
                    }
                    start = event;
                } else if (!(element instanceof SequenceFlow
                        || element instanceof EndEvent
                        || element instanceof ExclusiveGateway
                        || element instanceof ParallelGateway
                        || isCompensationBoundary(element))) {
                    throw invalid(describe(element) + " is not supported");
                }
            }
            if (start == null) {
                throw invalid("it has no start event");
            }
            if (tasks.isEmpty()) {
                throw invalid("it has no tasks");
            }
        }

        private void nameSteps() {
            var tasksPerService = new HashMap<String, Integer>();
            tasks.forEach(task -> tasksPerService.merge(serviceOf(task), 1, Integer::sum));
            for (var task : tasks) {
                var service = serviceOf(task);
                stepIds.put(task, tasksPerService.get(service) == 1 ? service : task.getId());
            }
        }

        private Task firstTask() {
            if (start.getOutgoing().size() != 1) {
                throw invalid("its start event must lead to exactly one task");
            }
            if (!(start.getOutgoing().iterator().next().getTarget() instanceof Task task)) {
                throw invalid("its start event must lead straight to a task");
            }
            return task;
        }

        /** Draws the edges leaving a task, through the gateway that may follow it. */
        private void followFrom(Task task) {
            var outgoing = task.getOutgoing();
            if (outgoing.size() > 1) {
                throw invalid("task " + task.getId() + " has " + outgoing.size()
                        + " outgoing flows; draw a gateway to choose between them or run them in parallel");
            }
            if (outgoing.isEmpty()) {
                throw invalid("task " + task.getId() + " leads nowhere; end the flow with an end event");
            }
            var next = outgoing.iterator().next().getTarget();
            if (next instanceof ExclusiveGateway choice && choice.getOutgoing().size() > 1) {
                choose(task, choice);
                return;
            }
            if (next instanceof ParallelGateway fork && fork.getOutgoing().size() > 1) {
                fork(task, fork);
                return;
            }
            reach(next).ifPresent(target -> edges.add(new String[] {stepIds.get(task), target, "plain", null}));
        }

        private void choose(Task task, ExclusiveGateway choice) {
            if (choice.getIncoming().size() != 1) {
                throw invalid("exclusive gateway " + choice.getId() + " both merges and splits; draw two gateways");
            }
            splits.put(task, Split.EXCLUSIVE);
            for (var flow : choice.getOutgoing()) {
                var target = reach(flow.getTarget()).orElseThrow(() -> invalid(
                        "the flow " + flow.getId() + " out of choice " + choice.getId() + " ends the flow without a task"));
                if (flow.equals(choice.getDefault())) {
                    edges.add(new String[] {stepIds.get(task), target, "default", null});
                } else if (flow.getConditionExpression() == null
                        || flow.getConditionExpression().getTextContent().isBlank()) {
                    throw invalid("the flow " + flow.getId() + " out of choice " + choice.getId()
                            + " needs a condition, or must be the gateway's default flow");
                } else {
                    edges.add(new String[] {stepIds.get(task), target, "condition",
                            flow.getConditionExpression().getTextContent().strip()});
                }
            }
        }

        private void fork(Task task, ParallelGateway fork) {
            if (fork.getIncoming().size() != 1) {
                throw invalid("parallel gateway " + fork.getId() + " both joins and forks; draw two gateways");
            }
            splits.put(task, Split.PARALLEL);
            for (var flow : fork.getOutgoing()) {
                var target = reach(flow.getTarget()).orElseThrow(() -> invalid(
                        "the branch " + flow.getId() + " of fork " + fork.getId() + " ends the flow without meeting the others"));
                edges.add(new String[] {stepIds.get(task), target, "plain", null});
            }
        }

        private Optional<Duration> joinTimeout() {
            var timeout = process.getAttributeValueNs(NAMESPACE, "joinTimeout");
            if (timeout == null || timeout.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(Duration.parse(timeout.strip()));
            } catch (DateTimeParseException e) {
                throw invalid("its joinTimeout '" + timeout + "' is not an ISO-8601 duration such as PT30S");
            }
        }

        private Optional<Integer> maxIterations() {
            var limit = process.getAttributeValueNs(NAMESPACE, "maxIterations");
            if (limit == null || limit.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(Integer.parseInt(limit.strip()));
            } catch (NumberFormatException e) {
                throw invalid("its maxIterations '" + limit + "' is not a whole number");
            }
        }

        /** The step a path leads to, passing merging gateways; empty if it ends the flow. */
        private Optional<String> reach(FlowNode node) {
            return reach(node, new HashSet<>());
        }

        private Optional<String> reach(FlowNode node, Set<FlowNode> passed) {
            if (node instanceof Task task) {
                return Optional.of(stepIds.get(task));
            }
            if (node instanceof EndEvent) {
                return Optional.empty();
            }
            if (node instanceof ExclusiveGateway merge && merge.getOutgoing().size() == 1) {
                if (!passed.add(merge)) {
                    throw invalid("gateway " + merge.getId() + " lies on a loop with no task in it");
                }
                return reach(merge.getOutgoing().iterator().next().getTarget(), passed);
            }
            if (node instanceof ParallelGateway join && join.getIncoming().size() > 1 && join.getOutgoing().size() == 1) {
                if (!(join.getOutgoing().iterator().next().getTarget() instanceof Task task)) {
                    throw invalid("parallel gateway " + join.getId()
                            + " must lead straight to a task, which runs once the branches meet");
                }
                joins.add(task);
                return Optional.of(stepIds.get(task));
            }
            throw invalid(describe(node) + " must follow a task directly");
        }

        private String serviceOf(Task task) {
            var service = task.getAttributeValueNs(NAMESPACE, "service");
            if (service == null || service.isBlank()) {
                service = task.getName();
            }
            if (service == null || service.isBlank()) {
                throw invalid("task " + task.getId() + " names no service; give it a name or a choreo:service");
            }
            service = service.strip();
            if (!SERVICE_NAME.matcher(service).matches()) {
                throw invalid("task " + task.getId() + " runs service '" + service
                        + "', but a service name may only hold letters, digits, '.', '_' and '-'");
            }
            return service;
        }

        private static boolean isCompensationBoundary(FlowElement element) {
            return element instanceof BoundaryEvent event
                    && event.getEventDefinitions().stream().anyMatch(CompensateEventDefinition.class::isInstance);
        }

        private static String describe(FlowElement element) {
            return element.getElementType().getTypeName() + " " + element.getId();
        }

        private InvalidPlanException invalid(String reason) {
            return new InvalidPlanException("flow " + flowName + " cannot run: " + reason);
        }
    }
}
