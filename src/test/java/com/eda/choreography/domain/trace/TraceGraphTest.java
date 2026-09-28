package com.eda.choreography.domain.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TraceGraphTest {

    private static final String CORRELATION = "corr-1";

    @Test
    void drawsParentToChildEdgesIncludingJoinsWithSeveralParents() {
        var a = done("A");
        var b = done("B", "A");
        var c = done("C", "A");
        var j = done("J", "B", "C");

        var graph = TraceGraph.of(List.of(a, b, c, j));

        assertThat(graph.childrenOf("A")).containsExactlyInAnyOrder(b, c);
        assertThat(graph.parentsOf("J")).containsExactlyInAnyOrder(b, c);
        assertThat(graph.parentsOf("A")).isEmpty();
        assertThat(graph.childrenOf("J")).isEmpty();
        assertThat(graph.correlationId()).contains(CORRELATION);
    }

    @Test
    void toleratesAChildArrivingBeforeItsParent() {
        var a = done("A");
        var b = done("B", "A");
        var c = done("C", "B");

        var graph = TraceGraph.of(List.of(c, b, a));

        assertThat(graph.entries()).containsExactlyInAnyOrder(a, b, c);
        assertThat(graph.childrenOf("A")).containsExactly(b);
        assertThat(graph.childrenOf("B")).containsExactly(c);
    }

    @Test
    void collapsesIdenticalDuplicatesFromMergedBranchTraces() {
        // At a join both branch traces carry the common ancestor A.
        var a = done("A");
        var b = done("B", "A");
        var c = done("C", "A");

        var graph = TraceGraph.of(List.of(a, b, done("A"), c));

        assertThat(graph.entries()).containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    void emptyTraceIsAValidGraphWithNoInstance() {
        var graph = TraceGraph.of(List.of());

        assertThat(graph.entries()).isEmpty();
        assertThat(graph.correlationId()).isEmpty();
    }

    private static TraceEntry done(String id, String... parents) {
        return TraceEntry.completed(id, CORRELATION, "svc-" + id, "result-" + id, parents);
    }
}
