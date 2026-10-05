package com.eda.choreography.domain.compensation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.join.InMemoryJoinStateStore;
import com.eda.choreography.domain.join.JoinStateMachine;
import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.trace.TraceEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Compensation walks the trace backwards with no coordinator: every service undoes its own
 * entry once the whole stage before it is done, then hands on to the next stage.
 */
class CompensationRunnerTest {

    private final Bus bus = new Bus();

    @Test
    void aLinearInstanceIsUndoneNewestFirst() {
        var failed = start().recordStep("A", Map.of()).recordStep("B", Map.of()).recordFailure("C");

        new CompensationTrigger(bus).trigger(failed, "run-1");
        bus.deliverAll();

        assertThat(bus.undone).containsExactly("B", "A");
        assertThat(bus.compensated).containsExactly(failed);
    }

    @Test
    void theStepBeforeAForkWaitsUntilEverySuccessfulSiblingIsUndone() {
        var forked = start().recordStep("A", Map.of());
        var merged = ChoreographyMessage.merge(List.of(
                forked.recordStep("B", Map.of()), forked.recordStep("C", Map.of()), forked.recordFailure("D")));

        new CompensationTrigger(bus).trigger(merged, "run-1");
        bus.deliverOne();

        assertThat(bus.undone).hasSize(1).isSubsetOf("B", "C");
        bus.deliverAll();
        assertThat(bus.undone).hasSize(3).endsWith("A");
        assertThat(bus.undone.subList(0, 2)).containsExactlyInAnyOrder("B", "C");
        assertThat(bus.compensated).hasSize(1);
    }

    @Test
    void anInstanceWithNothingToUndoIsReportedAtOnce() {
        var failed = start().recordFailure("A");

        new CompensationTrigger(bus).trigger(failed, "run-1");

        assertThat(bus.pending).isEmpty();
        assertThat(bus.undone).isEmpty();
        assertThat(bus.compensated).containsExactly(failed);
    }

    @Test
    void aRedeliveredRequestUndoesAgainAndHandsOnAgain() {
        // The first delivery may have crashed after undoing but before handing on.
        var failed = start().recordStep("A", Map.of()).recordStep("B", Map.of()).recordFailure("C");
        new CompensationTrigger(bus).trigger(failed, "run-1");
        var request = bus.pending.peekFirst();

        bus.deliverAll();
        bus.runners.get("B").handle(request);
        bus.deliverAll();

        assertThat(bus.undone).containsExactly("B", "A", "B", "A");
        assertThat(bus.compensated).hasSize(2);
    }

    @Test
    void twoRunsOverOneInstanceAreCountedApart() {
        var forked = start().recordStep("A", Map.of());
        var merged = ChoreographyMessage.merge(List.of(forked.recordStep("B", Map.of()), forked.recordStep("C", Map.of())));

        new CompensationTrigger(bus).trigger(merged, "run-1");
        new CompensationTrigger(bus).trigger(forked.recordStep("B", Map.of()), "run-2");
        bus.deliverAll();

        assertThat(bus.undone).filteredOn("A"::equals).hasSize(2);
    }

    @Test
    void aServiceOnlyUndoesItsOwnSteps() {
        var instance = start().recordStep("A", Map.of());
        var request = new CompensationRequest("run-1", instance.trace().get(0).id(), "run-1", instance);

        assertThatThrownBy(() -> bus.runners.get("B").handle(request)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ChoreographyMessage start() {
        return ChoreographyMessage.start("corr-1", "flow", Map.of());
    }

    /** Test double: routes requests to one runner per step through a queue, as a broker would. */
    private static final class Bus implements CompensationPublisher {

        final JoinStateMachine joins = new JoinStateMachine(new InMemoryJoinStateStore());
        final Map<String, CompensationRunner> runners = new HashMap<>();
        final Deque<CompensationRequest> pending = new ArrayDeque<>();
        final List<String> undone = new ArrayList<>();
        final List<ChoreographyMessage> compensated = new ArrayList<>();

        Bus() {
            for (var step : List.of("A", "B", "C", "D")) {
                runners.put(step, new CompensationRunner(step, this::recordUndo, joins, this));
            }
        }

        private void recordUndo(TraceEntry entry, ChoreographyMessage instance) {
            undone.add(entry.stepId());
        }

        @Override
        public void publish(String stepId, CompensationRequest request) {
            pending.addLast(request);
        }

        @Override
        public void publishCompensated(ChoreographyMessage instance) {
            compensated.add(instance);
        }

        void deliverOne() {
            var request = pending.removeFirst();
            runners.get(request.entry().stepId()).handle(request);
        }

        void deliverAll() {
            while (!pending.isEmpty()) {
                deliverOne();
            }
        }
    }
}
