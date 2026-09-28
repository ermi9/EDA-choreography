package com.eda.choreography.domain.compensation;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.trace.TraceEntry;
import com.eda.choreography.domain.trace.TraceGraph;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The INC-1 exit tests. Each named scenario asserts the exact stages, and every scenario is
 * also checked against the general rule ({@link #assertDescendantsUndoneFirst}) so that a
 * wrong-but-plausible order cannot slip through a hand-written expectation.
 */
class CompensationOrderingTest {

    private static final String CORRELATION = "corr-1";

    // ---- the plan's required scenarios -------------------------------------------------

    @Test
    void linearTraceIsUndoneInReverse() {
        var a = done("A");
        var b = done("B", "A");
        var c = done("C", "B");

        var order = orderOf(a, b, c);

        assertThat(order.sequence()).containsExactly(c, b, a);
        assertThat(order.stages()).containsExactly(Set.of(c), Set.of(b), Set.of(a));
    }

    @Test
    void allSucceededForkBranchesAreUndoneBeforeTheForkingStep() {
        var a = done("A");
        var b = done("B", "A");
        var c = done("C", "A");
        var d = done("D", "A");

        var order = orderOf(a, b, c, d);

        assertThat(order.stages()).containsExactly(Set.of(b, c, d), Set.of(a));
        assertThat(order.sequence()).hasSize(4).last().isEqualTo(a);
    }

    @Test
    void failedBranchLeavesOnlySuccessfulSiblingsThenTheForkingStep() {
        var a = done("A");
        var b = done("B", "A");
        var c = done("C", "A");
        var d = failed("D", "A");

        var order = orderOf(a, b, c, d);

        assertThat(order.stages()).containsExactly(Set.of(b, c), Set.of(a));
        assertThat(order.sequence()).doesNotContain(d);
    }

    @Test
    void failedBranchThatLeftNoTraceEntryGivesTheSameOrder() {
        var a = done("A");
        var b = done("B", "A");
        var c = done("C", "A");

        assertThat(orderOf(a, b, c).stages()).containsExactly(Set.of(b, c), Set.of(a));
    }

    // ---- joins, uneven branches, and the general rule -----------------------------------

    @Test
    void joinIsUndoneBeforeEitherOfItsBranches() {
        var a = done("A");
        var b = done("B", "A");
        var c = done("C", "A");
        var j = done("J", "B", "C");
        var k = done("K", "J");

        var order = orderOf(a, b, c, j, k);

        assertThat(order.stages())
                .containsExactly(Set.of(k), Set.of(j), Set.of(b, c), Set.of(a));
    }

    @Test
    void longerBranchIsUndoneInReverseWhileTheForkWaitsForBoth() {
        // A -> fork(B -> B2, C): B2 must precede B; A must come after B, B2 and C.
        var a = done("A");
        var b = done("B", "A");
        var b2 = done("B2", "B");
        var c = done("C", "A");

        var order = orderOf(a, b, b2, c);

        assertThat(order.sequence()).hasSize(4).last().isEqualTo(a);
        assertThat(order.sequence().indexOf(b2)).isLessThan(order.sequence().indexOf(b));
    }

    @Test
    void failureAfterTheForkUndoesSiblingsThenEveryPreForkStep() {
        // P -> Q -> fork(B, C, D); D fails. Undo {B, C}, then Q, then P.
        var p = done("P");
        var q = done("Q", "P");
        var b = done("B", "Q");
        var c = done("C", "Q");
        var d = failed("D", "Q");

        assertThat(orderOf(p, q, b, c, d).stages())
                .containsExactly(Set.of(b, c), Set.of(q), Set.of(p));
    }

    @Test
    void emptyTraceAndAllFailedTraceHaveNothingToUndo() {
        assertThat(CompensationOrdering.of(TraceGraph.of(List.of())).isEmpty()).isTrue();
        assertThat(orderOf(failed("A")).isEmpty()).isTrue();
    }

    // ---- helpers -----------------------------------------------------------------------

    private static TraceEntry done(String id, String... parents) {
        return TraceEntry.completed(id, CORRELATION, "svc-" + id, "result-" + id, parents);
    }

    private static TraceEntry failed(String id, String... parents) {
        return TraceEntry.failed(id, CORRELATION, "svc-" + id, parents);
    }

    /** Orders the trace and checks the general invariant on the result before returning it. */
    private static CompensationOrder orderOf(TraceEntry... entries) {
        var graph = TraceGraph.of(List.of(entries));
        var order = CompensationOrdering.of(graph);
        assertDescendantsUndoneFirst(graph, order);
        return order;
    }

    /**
     * The definition of a correct compensation order, independent of any scenario: every entry
     * that needs compensation appears exactly once, nothing else appears, and each entry is
     * undone only after all of its compensable descendants, which sit in strictly earlier stages.
     */
    private static void assertDescendantsUndoneFirst(TraceGraph graph, CompensationOrder order) {
        var sequence = order.sequence();
        var expected = graph.entries().stream().filter(TraceEntry::needsCompensation).toList();
        assertThat(sequence).containsExactlyInAnyOrderElementsOf(expected);

        var stageOf = new java.util.HashMap<TraceEntry, Integer>();
        for (int i = 0; i < order.stages().size(); i++) {
            assertThat(order.stages().get(i)).as("stage %d", i).isNotEmpty();
            for (var entry : order.stages().get(i)) {
                stageOf.put(entry, i);
            }
        }
        for (var entry : sequence) {
            for (var descendant : descendantsOf(graph, entry)) {
                if (descendant.needsCompensation()) {
                    assertThat(stageOf.get(descendant))
                            .as("%s must be undone in an earlier stage than its ancestor %s",
                                    descendant.id(), entry.id())
                            .isLessThan(stageOf.get(entry));
                }
            }
        }
    }

    private static Set<TraceEntry> descendantsOf(TraceGraph graph, TraceEntry root) {
        var seen = new HashSet<TraceEntry>();
        Deque<TraceEntry> todo = new ArrayDeque<>(graph.childrenOf(root.id()));
        while (!todo.isEmpty()) {
            var next = todo.pop();
            if (seen.add(next)) {
                todo.addAll(graph.childrenOf(next.id()));
            }
        }
        return seen;
    }
}
