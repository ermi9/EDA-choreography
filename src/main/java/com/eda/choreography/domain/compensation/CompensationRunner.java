package com.eda.choreography.domain.compensation;

import com.eda.choreography.domain.join.BranchArrival;
import com.eda.choreography.domain.join.JoinKey;
import com.eda.choreography.domain.join.JoinStateMachine;
import com.eda.choreography.domain.trace.TraceGraph;
import java.util.Objects;

/**
 * One service's part in undoing an instance.
 *
 * <p>Each request names an entry in stage {@code n} of the compensation order. The entry may
 * only be undone once every entry of stage {@code n - 1} has been, and each of those sends this
 * service a request when it is done. So the runner treats the requests for one entry as
 * branches of a join and undoes the entry when the join fires. Then it sends a request to every
 * entry of stage {@code n + 1}, or reports the instance as compensated after the last stage.
 * The order comes from the trace in the request alone; nothing coordinates the services.
 *
 * <p>A request that arrives again after the entry was undone is handled again. The first run may
 * have stopped between undoing and handing on, and the undo is idempotent.
 */
public final class CompensationRunner {

    private final String stepId;
    private final CompensationAction action;
    private final JoinStateMachine joins;
    private final CompensationPublisher publisher;

    public CompensationRunner(
            String stepId, CompensationAction action, JoinStateMachine joins, CompensationPublisher publisher) {
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("stepId must be non-blank");
        }
        this.stepId = stepId;
        this.action = Objects.requireNonNull(action, "action");
        this.joins = Objects.requireNonNull(joins, "joins");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    public String stepId() {
        return stepId;
    }

    public void handle(CompensationRequest request) {
        var entry = request.entry();
        if (!entry.stepId().equals(stepId)) {
            throw new IllegalArgumentException(
                    "step " + stepId + " was asked to undo entry " + entry.id() + " of step " + entry.stepId());
        }
        var stages = CompensationOrdering.of(TraceGraph.of(request.instance().trace()), request.alreadyUndone()).stages();
        int stage = 0;
        while (!stages.get(stage).contains(entry)) {
            stage++;
        }

        int waitingFor = stage == 0 ? 1 : stages.get(stage - 1).size();
        var key = new JoinKey(request.instance().correlationId(), "compensate:" + request.runId() + ":" + entry.id());
        var outcome = joins.arrive(new BranchArrival(key, request.previousId(), waitingFor));
        if (!outcome.state().fired()) {
            return;
        }

        action.undo(entry, request.instance());
        if (stage + 1 == stages.size()) {
            publisher.publishCompensated(request.instance());
            return;
        }
        for (var next : stages.get(stage + 1)) {
            publisher.publish(next.stepId(), new CompensationRequest(
                    request.runId(), next.id(), entry.id(), request.instance(), request.alreadyUndone()));
        }
    }
}
