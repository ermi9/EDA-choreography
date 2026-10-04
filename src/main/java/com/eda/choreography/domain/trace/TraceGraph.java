package com.eda.choreography.domain.trace;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The execution trace of one choreography instance, reconstructed as a DAG.
 *
 * <p>Built from a flat collection of {@link TraceEntry entries} in any order: all entries are
 * indexed first and edges drawn afterwards, so a child may arrive before the parent it names.
 * Identical duplicates collapse (at a join, every branch trace carries the shared ancestors).
 *
 * <p>A constructed graph is guaranteed well-formed: every parent is present, all entries share
 * one correlation id, ids are unique, and there is no cycle. Anything else is rejected with
 * {@link MalformedTraceException} rather than repaired, because a compensation order derived
 * from a guessed trace could undo steps in the wrong order.
 */
public final class TraceGraph {

    /** Ascending id order: makes iteration and derived orders reproducible, carries no meaning. */
    private static final Comparator<TraceEntry> BY_ID = Comparator.comparing(TraceEntry::id);

    private final Map<String, TraceEntry> byId;
    private final Map<String, Set<TraceEntry>> children;

    private TraceGraph(Map<String, TraceEntry> byId, Map<String, Set<TraceEntry>> children) {
        this.byId = byId;
        this.children = children;
    }

    public static TraceGraph of(Collection<TraceEntry> entries) {
        var byId = index(entries);
        var children = new HashMap<String, Set<TraceEntry>>();
        for (var entry : byId.values()) {
            for (var parentId : entry.parents()) {
                if (!byId.containsKey(parentId)) {
                    throw new MalformedTraceException(
                            "entry " + entry.id() + " names parent " + parentId + " which is not in the trace");
                }
                children.computeIfAbsent(parentId, id -> new LinkedHashSet<>()).add(entry);
            }
        }
        var graph = new TraceGraph(byId, children);
        graph.requireAcyclic();
        return graph;
    }

    /** The instance this trace belongs to; empty only for an empty trace. */
    public Optional<String> correlationId() {
        return byId.values().stream().findFirst().map(TraceEntry::correlationId);
    }

    public Set<TraceEntry> entries() {
        return Set.copyOf(byId.values());
    }

    /**
     * The entries no other entry names as a parent: where the instance currently stands. One
     * entry on a linear path, every open branch end after a fork, empty for an empty trace.
     */
    public Set<TraceEntry> leaves() {
        return byId.values().stream()
                .filter(entry -> !children.containsKey(entry.id()))
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<TraceEntry> parentsOf(String id) {
        return require(id).parents().stream().map(byId::get).collect(Collectors.toUnmodifiableSet());
    }

    public Set<TraceEntry> childrenOf(String id) {
        require(id);
        return Set.copyOf(children.getOrDefault(id, Set.of()));
    }

    private TraceEntry require(String id) {
        var entry = byId.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("no entry " + id + " in this trace");
        }
        return entry;
    }

    private static Map<String, TraceEntry> index(Collection<TraceEntry> entries) {
        var byId = new LinkedHashMap<String, TraceEntry>();
        String correlationId = null;
        for (var entry : entries.stream().sorted(BY_ID).toList()) {
            if (correlationId == null) {
                correlationId = entry.correlationId();
            } else if (!correlationId.equals(entry.correlationId())) {
                throw new MalformedTraceException("trace mixes instances " + correlationId
                        + " and " + entry.correlationId() + " (entry " + entry.id() + ")");
            }
            var previous = byId.putIfAbsent(entry.id(), entry);
            if (previous != null && !previous.equals(entry)) {
                throw new MalformedTraceException(
                        "two different entries share id " + entry.id() + ": " + previous + " vs " + entry);
            }
        }
        return byId;
    }

    /** Kahn's algorithm forward from the roots; anything never reached lies on a cycle. */
    private void requireAcyclic() {
        var pendingParents = new HashMap<String, Integer>();
        Deque<String> ready = new ArrayDeque<>();
        for (var entry : byId.values()) {
            pendingParents.put(entry.id(), entry.parents().size());
            if (entry.parents().isEmpty()) {
                ready.add(entry.id());
            }
        }
        int visited = 0;
        while (!ready.isEmpty()) {
            var id = ready.poll();
            visited++;
            for (var child : children.getOrDefault(id, Set.of())) {
                if (pendingParents.merge(child.id(), -1, Integer::sum) == 0) {
                    ready.add(child.id());
                }
            }
        }
        if (visited != byId.size()) {
            var onCycle = pendingParents.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
            throw new MalformedTraceException("trace contains a cycle; entries on or behind it: " + onCycle);
        }
    }
}
