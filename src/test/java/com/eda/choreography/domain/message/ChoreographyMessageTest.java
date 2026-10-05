package com.eda.choreography.domain.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.trace.MalformedTraceException;
import com.eda.choreography.domain.trace.TraceEntry;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChoreographyMessageTest {

    private static final String CORRELATION = "order-42";
    private static final String FLOW = "checkout";
    private static final Map<String, Object> REQUEST = Map.of("sku", "X-1", "quantity", 10);
    private static final Map<String, Object> RESERVED = Map.of("quantity", 10);
    private static final Map<String, Object> PRICED = Map.of("amount", 30);
    private static final Map<String, Object> TAXED = Map.of("total", 33);

    @Test
    void aStartedInstanceCarriesTheRequestButNoTraceOrResults() {
        var message = ChoreographyMessage.start(CORRELATION, FLOW, REQUEST);

        assertThat(message.correlationId()).isEqualTo(CORRELATION);
        assertThat(message.flowName()).isEqualTo(FLOW);
        assertThat(message.input()).isEqualTo(REQUEST);
        assertThat(message.trace()).isEmpty();
        assertThat(message.results()).isEmpty();
    }

    @Test
    void theFirstStepIsARootEntryWhoseResultTravelsUnderItsResultRef() {
        var message = ChoreographyMessage.start(CORRELATION, FLOW, Map.of()).recordStep("A", RESERVED);

        assertThat(message.trace()).singleElement().satisfies(entry -> {
            assertThat(entry.stepId()).isEqualTo("A");
            assertThat(entry.correlationId()).isEqualTo(CORRELATION);
            assertThat(entry.parents()).isEmpty();
            assertThat(entry.needsCompensation()).isTrue();
            assertThat(message.results()).containsExactly(Map.entry(entry.resultRef(), RESERVED));
        });
    }

    @Test
    void eachStepIsChainedToTheStepThatTriggeredIt() {
        var message = ChoreographyMessage.start(CORRELATION, FLOW, Map.of())
                .recordStep("A", RESERVED)
                .recordStep("B", PRICED)
                .recordStep("C", TAXED);

        var a = entryFor(message, "A");
        var b = entryFor(message, "B");
        var c = entryFor(message, "C");
        assertThat(b.parents()).containsExactly(a.id());
        assertThat(c.parents()).containsExactly(b.id());
        assertThat(message.resultOf("A")).contains(RESERVED);
        assertThat(message.resultOf("B")).contains(PRICED);
        assertThat(message.resultOf("C")).contains(TAXED);
        assertThat(message.resultOf("D")).isEmpty();
    }

    @Test
    void theRequestTravelsUnchangedThroughEveryStep() {
        var message = ChoreographyMessage.start(CORRELATION, FLOW, REQUEST)
                .recordStep("A", RESERVED)
                .recordStep("B", PRICED);

        assertThat(message.input()).isEqualTo(REQUEST);
    }

    @Test
    void theRequestIsAJsonObject() {
        assertThatThrownBy(() -> ChoreographyMessage.start(CORRELATION, FLOW, null))
                .isInstanceOf(MalformedMessageException.class);
        assertThatThrownBy(() -> ChoreographyMessage.start(CORRELATION, FLOW, Map.of("on", LocalDate.of(2026, 10, 5))))
                .isInstanceOf(MalformedMessageException.class)
                .hasMessageContaining("on");
    }

    @Test
    void processingTheSameMessageTwiceRecordsTheSameEntry() {
        // A redelivered message must not fork the trace into two different histories.
        var received = ChoreographyMessage.start(CORRELATION, FLOW, Map.of()).recordStep("A", RESERVED);

        assertThat(received.recordStep("B", PRICED)).isEqualTo(received.recordStep("B", PRICED));
    }

    @Test
    void entryIdsDifferAcrossInstancesAndSteps() {
        var mine = ChoreographyMessage.start(CORRELATION, FLOW, Map.of()).recordStep("A", RESERVED);
        var theirs = ChoreographyMessage.start("order-43", FLOW, Map.of()).recordStep("A", RESERVED);
        var otherStep = ChoreographyMessage.start(CORRELATION, FLOW, Map.of()).recordStep("B", RESERVED);

        assertThat(mine.trace().get(0).id())
                .isNotEqualTo(theirs.trace().get(0).id())
                .isNotEqualTo(otherStep.trace().get(0).id());
    }

    @Test
    void aFailedStepIsChainedLikeAnyOtherButLeavesNothingToUndo() {
        var reserved = ChoreographyMessage.start(CORRELATION, FLOW, Map.of()).recordStep("A", RESERVED);

        var failed = reserved.recordFailure("B");

        var b = entryFor(failed, "B");
        assertThat(b.parents()).containsExactly(entryFor(reserved, "A").id());
        assertThat(b.needsCompensation()).isFalse();
        assertThat(failed.resultOf("B")).isEmpty();
        assertThat(failed.hasFailed()).isTrue();
        assertThat(reserved.hasFailed()).isFalse();
    }

    @Test
    void aFailureGetsAnotherIdThanACompletedRunOfTheSameStep() {
        // A step can complete, be redelivered and then fail; both entries may meet at a join.
        var reserved = ChoreographyMessage.start(CORRELATION, FLOW, Map.of()).recordStep("A", RESERVED);

        var failedId = entryFor(reserved.recordFailure("B"), "B").id();

        assertThat(failedId).isNotEqualTo(entryFor(reserved.recordStep("B", PRICED), "B").id());
        assertThat(reserved.recordFailure("B")).isEqualTo(reserved.recordFailure("B"));
    }

    @Test
    void aResultIsAJsonObjectThatCannotChangeAfterwards() {
        var nested = new HashMap<String, Object>(Map.of("risk", "LOW"));
        var message = ChoreographyMessage.start(CORRELATION, FLOW, Map.of()).recordStep("A", Map.of("assessment", nested));

        nested.put("risk", "HIGH");

        assertThat(message.resultOf("A")).contains(Map.of("assessment", Map.of("risk", "LOW")));
    }

    @Test
    void rejectsAResultJsonCannotRepresent() {
        var started = ChoreographyMessage.start(CORRELATION, FLOW, Map.of());

        assertThatThrownBy(() -> started.recordStep("A", Map.of("placed", LocalDate.of(2026, 10, 4))))
                .isInstanceOf(MalformedMessageException.class)
                .hasMessageContaining("placed");
    }

    @Test
    void rejectsATraceFromAnotherInstance() {
        var foreign = TraceEntry.completed("x", "order-99", "A", "x");

        assertThatThrownBy(() -> new ChoreographyMessage(CORRELATION, FLOW, Map.of(), List.of(foreign), Map.of("x", RESERVED)))
                .isInstanceOf(MalformedMessageException.class)
                .hasMessageContaining("order-99");
    }

    @Test
    void rejectsAResultNoTraceEntryRefersTo() {
        assertThatThrownBy(() -> new ChoreographyMessage(CORRELATION, FLOW, Map.of(), List.of(), Map.of("ghost", RESERVED)))
                .isInstanceOf(MalformedMessageException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void rejectsAMalformedTrace() {
        var dangling = TraceEntry.completed("b", CORRELATION, "B", "b", "missing-parent");

        assertThatThrownBy(() -> new ChoreographyMessage(CORRELATION, FLOW, Map.of(), List.of(dangling), Map.of("b", PRICED)))
                .isInstanceOf(MalformedTraceException.class);
    }

    @Test
    void rejectsBlankIdentity() {
        assertThatThrownBy(() -> ChoreographyMessage.start(" ", FLOW, Map.of())).isInstanceOf(MalformedMessageException.class);
        assertThatThrownBy(() -> ChoreographyMessage.start(CORRELATION, "", Map.of())).isInstanceOf(MalformedMessageException.class);
    }

    private static TraceEntry entryFor(ChoreographyMessage message, String stepId) {
        return message.trace().stream().filter(e -> e.stepId().equals(stepId)).findFirst().orElseThrow();
    }
}
