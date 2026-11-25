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
 * @param stepId        the plan step that executed
 * @param service       the logical service that executed it, which undoes it; when absent, the
 *                      service the step is named after
 * @param outcome       whether the step's effect was committed (and so must be undone)
 * @param resultRef     opaque reference to the step's result, handed to its compensation; may be null
 */
public record TraceEntry(
        String id,
        Set<String> parents,
        String correlationId,
        String stepId,
        String service,
        Outcome outcome,
        String resultRef) {

    /**
     * Whether a step's effect happened. Only {@link #COMPLETED} and {@link #UNCERTAIN} steps may
     * have anything to undo.
     *
     * <p>Scope boundary: steps are assumed atomic, so {@link #FAILED} means no side effect was
     * committed.
     *
     * <p>{@link #UNCERTAIN} means the step gave up without learning whether its effect happened,
     * such as a call whose answer never came. It fails the instance like a failure and is undone
     * like a completed step; undoing work that never happened must be harmless.
     *
     * <p>{@link #SKIPPED} means the step never ran: it was inside a fork that had already failed,
     * so it did no work and handed the instance straight to the join.
     */
    public enum Outcome {
        COMPLETED,
        FAILED,
        SKIPPED,
        UNCERTAIN
    }

    public TraceEntry {
        requireText(id, "id");
        requireText(correlationId, "correlationId");
        requireText(stepId, "stepId");
        if (service == null) {
            service = stepId;
        }
        requireText(service, "service");
        Objects.requireNonNull(outcome, "outcome");
        parents = Set.copyOf(Objects.requireNonNull(parents, "parents"));
        if (parents.contains(id)) {
            throw new MalformedTraceException("entry " + id + " lists itself as a parent");
        }
    }

    /** A completed step, run by the service it is named after. */
    public static TraceEntry completed(
            String id, String correlationId, String stepId, String resultRef, String... parents) {
        return new TraceEntry(id, Set.of(parents), correlationId, stepId, stepId, Outcome.COMPLETED, resultRef);
    }

    /** A failed step, of the service it is named after. */
    public static TraceEntry failed(String id, String correlationId, String stepId, String... parents) {
        return new TraceEntry(id, Set.of(parents), correlationId, stepId, stepId, Outcome.FAILED, null);
    }

    /** A step that was skipped, of the service it is named after. */
    public static TraceEntry skipped(String id, String correlationId, String stepId, String... parents) {
        return new TraceEntry(id, Set.of(parents), correlationId, stepId, stepId, Outcome.SKIPPED, null);
    }

    /**
     * A step that may have happened, of the service it is named after.
     *
     * @param resultRef the reference the step's result would have had, had it been seen
     */
    public static TraceEntry uncertain(
            String id, String correlationId, String stepId, String resultRef, String... parents) {
        return new TraceEntry(id, Set.of(parents), correlationId, stepId, stepId, Outcome.UNCERTAIN, resultRef);
    }

    /** This entry, run by the given service. */
    public TraceEntry runBy(String service) {
        return new TraceEntry(id, parents, correlationId, stepId, service, outcome, resultRef);
    }

    public boolean needsCompensation() {
        return outcome == Outcome.COMPLETED || outcome == Outcome.UNCERTAIN;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new MalformedTraceException(name + " must be non-blank");
        }
    }
}
