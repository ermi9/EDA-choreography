package com.eda.choreography.domain.message;

import com.eda.choreography.domain.trace.TraceEntry;
import com.eda.choreography.domain.trace.TraceGraph;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What one step hands to the next: which instance this is, which flow it runs, the request
 * that started it, everything that has happened so far, and what each step produced.
 *
 * <p>The trace travels as full ancestry, so any step can rebuild the instance's history (and
 * its compensation order) from the message alone, with no shared store.
 *
 * <p>Each step's result is a JSON object (see {@link JsonObjects}), so conditions later in the
 * flow can test its fields. Results are keyed by the {@link TraceEntry#resultRef()} of the
 * entry that produced them, not by step name and not as a single payload field. Two branches
 * of a fork therefore never collide, and a join can merge its branches' messages by taking the
 * union of their traces and of their results.
 *
 * @param correlationId the choreography instance
 * @param flowName      the flow whose plan this instance runs
 * @param input         the request that started the instance, as a JSON object; never changes
 * @param trace         every step executed so far, as a well-formed DAG
 * @param results       each step's result as a JSON object, keyed by the producing entry's {@code resultRef}
 */
public record ChoreographyMessage(
        String correlationId,
        String flowName,
        Map<String, Object> input,
        List<TraceEntry> trace,
        Map<String, Map<String, Object>> results) {

    public ChoreographyMessage {
        requireText(correlationId, "correlationId");
        requireText(flowName, "flowName");
        input = JsonObjects.copyOf(input);
        trace = List.copyOf(trace);
        results = copyResults(results);
        var graph = TraceGraph.of(trace);
        graph.correlationId()
                .filter(traced -> !traced.equals(correlationId))
                .ifPresent(traced -> {
                    throw new MalformedMessageException(
                            "message for " + correlationId + " carries the trace of " + traced);
                });
        var referenced = trace.stream().map(TraceEntry::resultRef).collect(Collectors.toSet());
        for (var ref : results.keySet()) {
            if (!referenced.contains(ref)) {
                throw new MalformedMessageException("result " + ref + " is not referenced by any trace entry");
            }
        }
    }

    /** The message that starts a new instance of a flow with the given request, before any step has run. */
    public static ChoreographyMessage start(String correlationId, String flowName, Map<String, ?> input) {
        return new ChoreographyMessage(correlationId, flowName, JsonObjects.copyOf(input), List.of(), Map.of());
    }

    /**
     * This message with one more completed step appended. The step's parents are the trace's
     * current leaves: the previous step on a linear path, every branch end at a join.
     *
     * <p>The entry id is derived from the instance, the step and its parents rather than drawn
     * at random. Delivery is at-least-once, so a step may process the same message twice; both
     * runs then record the same entry, and the duplicate collapses in the trace instead of
     * becoming a second history.
     */
    public ChoreographyMessage recordStep(String stepId, Map<String, ?> result) {
        var parents = currentLeafIds();
        var id = entryId(stepId, parents, "");
        var entry = TraceEntry.completed(id, correlationId, stepId, id, parents.toArray(String[]::new));

        var nextTrace = new ArrayList<>(trace);
        nextTrace.add(entry);
        var nextResults = new HashMap<>(results);
        nextResults.put(entry.resultRef(), JsonObjects.copyOf(result));
        return new ChoreographyMessage(correlationId, flowName, input, nextTrace, nextResults);
    }

    /**
     * This message with a failed step appended: the step gave up and committed nothing, so the
     * entry has no result and nothing to undo. It still takes part in the trace, which is how
     * a join and the compensation order learn that the branch failed.
     *
     * <p>The id is derived like a completed entry's, but from a different name, because a step
     * may complete, be redelivered and only then fail.
     */
    public ChoreographyMessage recordFailure(String stepId) {
        var parents = currentLeafIds();
        var id = entryId(stepId, parents, "|failed");
        var nextTrace = new ArrayList<>(trace);
        nextTrace.add(TraceEntry.failed(id, correlationId, stepId, parents.toArray(String[]::new)));
        return new ChoreographyMessage(correlationId, flowName, input, nextTrace, results);
    }

    /**
     * The message a join continues with: the union of its branches' traces and results. The
     * branches share their history up to the fork, and those entries are kept once. The trace
     * is ordered by entry id, so the merge does not depend on the order branches arrived in.
     *
     * <p>If two branches carry different results for the same entry (a step before the fork ran
     * twice and answered differently each time), the result from the first branch given wins.
     * Callers pass branches in a stable order so that choice is repeatable.
     */
    public static ChoreographyMessage merge(Collection<ChoreographyMessage> branches) {
        if (branches.isEmpty()) {
            throw new MalformedMessageException("there are no branches to merge");
        }
        var first = branches.iterator().next();
        var entries = new TreeMap<String, TraceEntry>();
        var results = new HashMap<String, Map<String, Object>>();
        for (var branch : branches) {
            if (!branch.correlationId.equals(first.correlationId)
                    || !branch.flowName.equals(first.flowName)
                    || !branch.input.equals(first.input)) {
                throw new MalformedMessageException("cannot merge a branch of " + branch.correlationId
                        + " (" + branch.flowName + ") into " + first.correlationId + " (" + first.flowName + ")");
            }
            for (var entry : branch.trace) {
                var known = entries.putIfAbsent(entry.id(), entry);
                if (known != null && !known.equals(entry)) {
                    throw new MalformedMessageException("branches disagree on entry " + entry.id());
                }
            }
            branch.results.forEach(results::putIfAbsent);
        }
        return new ChoreographyMessage(
                first.correlationId, first.flowName, first.input, List.copyOf(entries.values()), results);
    }

    /** Whether any step of this instance has failed. */
    public boolean hasFailed() {
        return trace.stream().anyMatch(entry -> entry.outcome() == TraceEntry.Outcome.FAILED);
    }

    /** The result of the given step, if it ran. Meant for linear paths, where a step runs at most once. */
    public Optional<Map<String, Object>> resultOf(String stepId) {
        return trace.stream()
                .filter(entry -> entry.stepId().equals(stepId) && entry.resultRef() != null)
                .map(TraceEntry::resultRef)
                .map(results::get)
                .findFirst();
    }

    private static Map<String, Map<String, Object>> copyResults(Map<String, Map<String, Object>> results) {
        var copy = new HashMap<String, Map<String, Object>>();
        results.forEach((ref, result) -> copy.put(ref, JsonObjects.copyOf(result)));
        return Map.copyOf(copy);
    }

    private List<String> currentLeafIds() {
        return TraceGraph.of(trace).leaves().stream().map(TraceEntry::id).sorted().toList();
    }

    /** {@code outcomeSuffix} is empty for a completed step, so its ids are the ones INC-3 produced. */
    private String entryId(String stepId, List<String> sortedParents, String outcomeSuffix) {
        var name = correlationId + '|' + stepId + '|' + String.join(",", sortedParents) + outcomeSuffix;
        return stepId + ':' + UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new MalformedMessageException(name + " must be non-blank");
        }
    }
}
