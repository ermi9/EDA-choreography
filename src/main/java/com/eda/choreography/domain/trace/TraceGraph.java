package com.eda.choreography.domain.trace;

import java.util.Collection;
import java.util.Comparator;
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
                children.computeIfAbsent(parentId, id -> new LinkedHashSet<>()).add(entry);
            }
        }
        return new TraceGraph(byId, children);
    }

    /** The instance this trace belongs to; empty only for an empty trace. */
    public Optional<String> correlationId() {
        return byId.values().stream().findFirst().map(TraceEntry::correlationId);
    }

    public Set<TraceEntry> entries() {
        return Set.copyOf(byId.values());
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
        for (var entry : entries.stream().sorted(BY_ID).toList()) {
            byId.putIfAbsent(entry.id(), entry);
        }
        return byId;
    }

}
