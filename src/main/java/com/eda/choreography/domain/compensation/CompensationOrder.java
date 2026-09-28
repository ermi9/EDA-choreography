package com.eda.choreography.domain.compensation;

import com.eda.choreography.domain.trace.TraceEntry;
import java.util.List;
import java.util.Set;

/**
 * The order in which to undo a trace, as a sequence of stages.
 *
 * <p>Entries inside one stage have no ordering constraint between them (siblings of a fork)
 * and may be compensated in any order or concurrently. Every entry of stage {@code n} must be
 * compensated before any entry of stage {@code n + 1}.
 */
public record CompensationOrder(List<Set<TraceEntry>> stages) {

    public CompensationOrder {
        stages = List.copyOf(stages);
    }

    /** One valid total order consistent with the stages. */
    public List<TraceEntry> sequence() {
        return stages.stream().flatMap(Set::stream).toList();
    }

    public boolean isEmpty() {
        return stages.isEmpty();
    }
}
