package com.eda.choreography.domain.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StepRunnerTest {

    private static final LinearFlow FLOW = LinearFlow.of("checkout", "A", "B", "C");
    private static final Map<String, Object> RESERVED = Map.of("quantity", 10);
    private static final Map<String, Object> PRICED = Map.of("amount", 30);
    private static final Map<String, Object> TAXED = Map.of("total", 33);

    private final RecordingPublisher publisher = new RecordingPublisher();

    @Test
    void runsTheStepRecordsItAndPublishesToTheNextStep() {
        var incoming = ChoreographyMessage.start("order-42", "checkout", Map.of()).recordStep("A", RESERVED);
        var runner = new StepRunner("B", message -> PRICED, FLOW, publisher);

        runner.handle(incoming);

        assertThat(publisher.completed).isEmpty();
        assertThat(publisher.sent).singleElement().satisfies(sent -> {
            assertThat(sent.stepId()).isEqualTo("C");
            assertThat(sent.message()).isEqualTo(incoming.recordStep("B", PRICED));
        });
    }

    @Test
    void theStepWorksOnTheResultsAccumulatedSoFar() {
        var incoming = ChoreographyMessage.start("order-42", "checkout", Map.of()).recordStep("A", RESERVED);
        var tripled = new StepRunner(
                "B", message -> Map.of("amount", 3 * (int) message.resultOf("A").orElseThrow().get("quantity")),
                FLOW, publisher);

        tripled.handle(incoming);

        assertThat(publisher.sent.get(0).message().resultOf("B")).contains(PRICED);
    }

    @Test
    void theLastStepReportsTheInstanceAsCompleted() {
        var incoming = ChoreographyMessage.start("order-42", "checkout", Map.of())
                .recordStep("A", RESERVED)
                .recordStep("B", PRICED);
        var runner = new StepRunner("C", message -> TAXED, FLOW, publisher);

        runner.handle(incoming);

        assertThat(publisher.sent).isEmpty();
        assertThat(publisher.completed).containsExactly(incoming.recordStep("C", TAXED));
    }

    @Test
    void publishesTheSameMessageToEveryNextStep() {
        NextSteps toBoth = (stepId, message) -> List.of("B", "C");
        var runner = new StepRunner("A", message -> RESERVED, toBoth, publisher);

        runner.handle(ChoreographyMessage.start("order-42", "checkout", Map.of()));

        assertThat(publisher.sent).extracting(RecordingPublisher.Sent::stepId).containsExactly("B", "C");
        assertThat(publisher.sent).extracting(RecordingPublisher.Sent::message).containsOnly(
                ChoreographyMessage.start("order-42", "checkout", Map.of()).recordStep("A", RESERVED));
    }

    @Test
    void aFailingStepPublishesNothing() {
        var runner = new StepRunner("A", message -> {
            throw new IllegalStateException("payment provider down");
        }, FLOW, publisher);

        assertThatThrownBy(() -> runner.handle(ChoreographyMessage.start("order-42", "checkout", Map.of())))
                .hasMessageContaining("payment provider down");
        assertThat(publisher.sent).isEmpty();
        assertThat(publisher.completed).isEmpty();
    }

    @Test
    void rejectsABlankStepId() {
        assertThatThrownBy(() -> new StepRunner(" ", message -> Map.of(), FLOW, publisher))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
