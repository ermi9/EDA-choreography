package com.eda.choreography.domain.compensation;

import com.eda.choreography.domain.trace.TraceEntry;
import com.eda.choreography.domain.trace.TraceGraph;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Derives the reverse-topological compensation order of a trace.
 *
 * <p>Kahn's algorithm on the edge-reversed trace graph, processed in waves: the first wave is
 * every entry with no children (the frontier of the execution), and an entry joins a later
 * wave only once all of its children have been placed. So a forking step waits for every
 * branch, a join is undone before the branches that fed it, and sibling branches that become
 * ready together share a stage with no order between them.
 *
 * <p>Failed entries take part in the ordering (they are nodes of the graph) but are left out
 * of the stages: a failed step committed nothing, so there is nothing to undo. Entries an
 * earlier compensation of the same instance already undid are left out the same way.
 */
public final class CompensationOrdering {

    private CompensationOrdering() {}

    public static CompensationOrder of(TraceGraph trace) {
        return of(trace, Set.of());
    }

    /** As {@link #of(TraceGraph)}, leaving out the entries whose ids are in {@code alreadyUndone}. */
    public static CompensationOrder of(TraceGraph trace, Set<String> alreadyUndone) {
        var pendingChildren = new HashMap<String, Integer>();
        var wave = new ArrayList<TraceEntry>();
        for (var entry : trace.entries()) {
            int childCount = trace.childrenOf(entry.id()).size();
            pendingChildren.put(entry.id(), childCount);
            if (childCount == 0) {
                wave.add(entry);
            }
        }

        var stages = new ArrayList<Set<TraceEntry>>();
        while (!wave.isEmpty()) {
            var stage = compensable(wave, alreadyUndone);
            if (!stage.isEmpty()) {
                stages.add(stage);
            }
            var next = new ArrayList<TraceEntry>();
            for (var entry : wave) {
                for (var parent : trace.parentsOf(entry.id())) {
                    if (pendingChildren.merge(parent.id(), -1, Integer::sum) == 0) {
                        next.add(parent);
                    }
                }
            }
            wave = next;
        }
        return new CompensationOrder(stages);
    }

    /** The wave's entries that need undoing, in id order so logs and replays are reproducible. */
    private static Set<TraceEntry> compensable(List<TraceEntry> wave, Set<String> alreadyUndone) {
        var stage = new LinkedHashSet<TraceEntry>();
        wave.stream()
                .filter(TraceEntry::needsCompensation)
                .filter(entry -> !alreadyUndone.contains(entry.id()))
                .sorted(Comparator.comparing(TraceEntry::id))
                .forEach(stage::add);
        return Collections.unmodifiableSet(stage);
    }
}
