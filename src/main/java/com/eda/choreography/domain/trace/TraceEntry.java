package com.eda.choreography.domain.trace;

import java.util.Objects;
import java.util.Set;

/**
 * One executed step of one choreography instance, as carried in the message trace.
 *
 * <p>An entry names its {@code parents} rather than its children: a step only knows what it
 * was triggered by, and a join is triggered by several branches, so {@code parents} is a set.
 * Edges are therefore drawn child-to-parent at write time and inverted by {@link TraceGraph}.
 *
 * @param id            unique id of this execution of the step within the instance
 * @param parents       ids of the entries this step was triggered by; empty for the first step
 * @param correlationId the choreography instance this entry belongs to
 * @param stepId        the plan step / logical service that executed
 * @param outcome       whether the step's effect was committed (and so must be undone)
 * @param resultRef     opaque reference to the step's result, handed to its compensation; may be null
 */
public record TraceEntry(
        String id,
        Set<String> parents,
        String correlationId,
        String stepId,
        Outcome outcome,
        String resultRef) {

    /** Whether a step's effect happened. Only {@link #COMPLETED} steps have anything to undo. */
    public enum Outcome {
        COMPLETED,
        FAILED
    }

    public TraceEntry {
        requireText(id, "id");
        requireText(correlationId, "correlationId");
        requireText(stepId, "stepId");
        Objects.requireNonNull(outcome, "outcome");
        parents = Set.copyOf(Objects.requireNonNull(parents, "parents"));
        if (parents.contains(id)) {
            throw new MalformedTraceException("entry " + id + " lists itself as a parent");
        }
    }

    public static TraceEntry completed(
            String id, String correlationId, String stepId, String resultRef, String... parents) {
        return new TraceEntry(id, Set.of(parents), correlationId, stepId, Outcome.COMPLETED, resultRef);
    }

    public static TraceEntry failed(String id, String correlationId, String stepId, String... parents) {
        return new TraceEntry(id, Set.of(parents), correlationId, stepId, Outcome.FAILED, null);
    }

    public boolean needsCompensation() {
        return outcome == Outcome.COMPLETED;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new MalformedTraceException(name + " must be non-blank");
        }
    }
}
