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
    private static final Map<String, Object> RESERVED = Map.of("quantity", 10);
    private static final Map<String, Object> PRICED = Map.of("amount", 30);
    private static final Map<String, Object> TAXED = Map.of("total", 33);

    @Test
    void aStartedInstanceHasNoTraceAndNoResults() {
        var message = ChoreographyMessage.start(CORRELATION, FLOW);

        assertThat(message.correlationId()).isEqualTo(CORRELATION);
        assertThat(message.flowName()).isEqualTo(FLOW);
        assertThat(message.trace()).isEmpty();
        assertThat(message.results()).isEmpty();
    }

    @Test
    void theFirstStepIsARootEntryWhoseResultTravelsUnderItsResultRef() {
        var message = ChoreographyMessage.start(CORRELATION, FLOW).recordStep("A", RESERVED);

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
        var message = ChoreographyMessage.start(CORRELATION, FLOW)
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
    void processingTheSameMessageTwiceRecordsTheSameEntry() {
        // A redelivered message must not fork the trace into two different histories.
        var received = ChoreographyMessage.start(CORRELATION, FLOW).recordStep("A", RESERVED);

        assertThat(received.recordStep("B", PRICED)).isEqualTo(received.recordStep("B", PRICED));
    }

    @Test
    void entryIdsDifferAcrossInstancesAndSteps() {
        var mine = ChoreographyMessage.start(CORRELATION, FLOW).recordStep("A", RESERVED);
        var theirs = ChoreographyMessage.start("order-43", FLOW).recordStep("A", RESERVED);
        var otherStep = ChoreographyMessage.start(CORRELATION, FLOW).recordStep("B", RESERVED);

        assertThat(mine.trace().get(0).id())
                .isNotEqualTo(theirs.trace().get(0).id())
                .isNotEqualTo(otherStep.trace().get(0).id());
    }

    @Test
    void aResultIsAJsonObjectThatCannotChangeAfterwards() {
        var nested = new HashMap<String, Object>(Map.of("risk", "LOW"));
        var message = ChoreographyMessage.start(CORRELATION, FLOW).recordStep("A", Map.of("assessment", nested));

        nested.put("risk", "HIGH");

        assertThat(message.resultOf("A")).contains(Map.of("assessment", Map.of("risk", "LOW")));
    }

    @Test
    void rejectsAResultJsonCannotRepresent() {
        var started = ChoreographyMessage.start(CORRELATION, FLOW);

        assertThatThrownBy(() -> started.recordStep("A", Map.of("placed", LocalDate.of(2026, 10, 4))))
                .isInstanceOf(MalformedMessageException.class)
                .hasMessageContaining("placed");
    }

    @Test
    void rejectsATraceFromAnotherInstance() {
        var foreign = TraceEntry.completed("x", "order-99", "A", "x");

        assertThatThrownBy(() -> new ChoreographyMessage(CORRELATION, FLOW, List.of(foreign), Map.of("x", RESERVED)))
                .isInstanceOf(MalformedMessageException.class)
                .hasMessageContaining("order-99");
    }

    @Test
    void rejectsAResultNoTraceEntryRefersTo() {
        assertThatThrownBy(() -> new ChoreographyMessage(CORRELATION, FLOW, List.of(), Map.of("ghost", RESERVED)))
                .isInstanceOf(MalformedMessageException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void rejectsAMalformedTrace() {
        var dangling = TraceEntry.completed("b", CORRELATION, "B", "b", "missing-parent");

        assertThatThrownBy(() -> new ChoreographyMessage(CORRELATION, FLOW, List.of(dangling), Map.of("b", PRICED)))
                .isInstanceOf(MalformedTraceException.class);
    }

    @Test
    void rejectsBlankIdentity() {
        assertThatThrownBy(() -> ChoreographyMessage.start(" ", FLOW)).isInstanceOf(MalformedMessageException.class);
        assertThatThrownBy(() -> ChoreographyMessage.start(CORRELATION, "")).isInstanceOf(MalformedMessageException.class);
    }

    private static TraceEntry entryFor(ChoreographyMessage message, String stepId) {
        return message.trace().stream().filter(e -> e.stepId().equals(stepId)).findFirst().orElseThrow();
    }
}
