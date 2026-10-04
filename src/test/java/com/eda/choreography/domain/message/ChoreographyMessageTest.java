package com.eda.choreography.domain.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.trace.MalformedTraceException;
import com.eda.choreography.domain.trace.TraceEntry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChoreographyMessageTest {

    private static final String CORRELATION = "order-42";
    private static final String FLOW = "checkout";

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
        var message = ChoreographyMessage.start(CORRELATION, FLOW).recordStep("A", "10");

        assertThat(message.trace()).singleElement().satisfies(entry -> {
            assertThat(entry.stepId()).isEqualTo("A");
            assertThat(entry.correlationId()).isEqualTo(CORRELATION);
            assertThat(entry.parents()).isEmpty();
            assertThat(entry.needsCompensation()).isTrue();
            assertThat(message.results()).containsExactly(Map.entry(entry.resultRef(), "10"));
        });
    }

    @Test
    void eachStepIsChainedToTheStepThatTriggeredIt() {
        var message = ChoreographyMessage.start(CORRELATION, FLOW)
                .recordStep("A", "10")
                .recordStep("B", "30")
                .recordStep("C", "33");

        var a = entryFor(message, "A");
        var b = entryFor(message, "B");
        var c = entryFor(message, "C");
        assertThat(b.parents()).containsExactly(a.id());
        assertThat(c.parents()).containsExactly(b.id());
        assertThat(message.resultOf("A")).contains("10");
        assertThat(message.resultOf("B")).contains("30");
        assertThat(message.resultOf("C")).contains("33");
        assertThat(message.resultOf("D")).isEmpty();
    }

    @Test
    void processingTheSameMessageTwiceRecordsTheSameEntry() {
        // A redelivered message must not fork the trace into two different histories.
        var received = ChoreographyMessage.start(CORRELATION, FLOW).recordStep("A", "10");

        assertThat(received.recordStep("B", "30")).isEqualTo(received.recordStep("B", "30"));
    }

    @Test
    void entryIdsDifferAcrossInstancesAndSteps() {
        var mine = ChoreographyMessage.start(CORRELATION, FLOW).recordStep("A", "10");
        var theirs = ChoreographyMessage.start("order-43", FLOW).recordStep("A", "10");
        var otherStep = ChoreographyMessage.start(CORRELATION, FLOW).recordStep("B", "10");

        assertThat(mine.trace().get(0).id())
                .isNotEqualTo(theirs.trace().get(0).id())
                .isNotEqualTo(otherStep.trace().get(0).id());
    }

    @Test
    void rejectsATraceFromAnotherInstance() {
        var foreign = TraceEntry.completed("x", "order-99", "A", "x");

        assertThatThrownBy(() -> new ChoreographyMessage(CORRELATION, FLOW, List.of(foreign), Map.of("x", "10")))
                .isInstanceOf(MalformedMessageException.class)
                .hasMessageContaining("order-99");
    }

    @Test
    void rejectsAResultNoTraceEntryRefersTo() {
        assertThatThrownBy(() -> new ChoreographyMessage(CORRELATION, FLOW, List.of(), Map.of("ghost", "10")))
                .isInstanceOf(MalformedMessageException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void rejectsAMalformedTrace() {
        var dangling = TraceEntry.completed("b", CORRELATION, "B", "b", "missing-parent");

        assertThatThrownBy(() -> new ChoreographyMessage(CORRELATION, FLOW, List.of(dangling), Map.of("b", "30")))
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
