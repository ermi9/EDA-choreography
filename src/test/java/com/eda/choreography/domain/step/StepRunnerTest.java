package com.eda.choreography.domain.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class StepRunnerTest {

    private static final LinearFlow FLOW = LinearFlow.of("checkout", "A", "B", "C");

    private final RecordingPublisher publisher = new RecordingPublisher();

    @Test
    void runsTheStepRecordsItAndPublishesToTheNextStep() {
        var incoming = ChoreographyMessage.start("order-42", "checkout").recordStep("A", "10");
        var runner = new StepRunner("B", message -> "30", FLOW, publisher);

        runner.handle(incoming);

        assertThat(publisher.completed).isEmpty();
        assertThat(publisher.sent).singleElement().satisfies(sent -> {
            assertThat(sent.stepId()).isEqualTo("C");
            assertThat(sent.message()).isEqualTo(incoming.recordStep("B", "30"));
        });
    }

    @Test
    void theStepWorksOnTheResultsAccumulatedSoFar() {
        var incoming = ChoreographyMessage.start("order-42", "checkout").recordStep("A", "10");
        var tripled = new StepRunner(
                "B", message -> String.valueOf(3 * Integer.parseInt(message.resultOf("A").orElseThrow())),
                FLOW, publisher);

        tripled.handle(incoming);

        assertThat(publisher.sent.get(0).message().resultOf("B")).contains("30");
    }

    @Test
    void theLastStepReportsTheInstanceAsCompleted() {
        var incoming = ChoreographyMessage.start("order-42", "checkout").recordStep("A", "10").recordStep("B", "30");
        var runner = new StepRunner("C", message -> "33", FLOW, publisher);

        runner.handle(incoming);

        assertThat(publisher.sent).isEmpty();
        assertThat(publisher.completed).containsExactly(incoming.recordStep("C", "33"));
    }

    @Test
    void publishesTheSameMessageToEveryNextStep() {
        NextSteps toBoth = (stepId, message) -> List.of("B", "C");
        var runner = new StepRunner("A", message -> "10", toBoth, publisher);

        runner.handle(ChoreographyMessage.start("order-42", "checkout"));

        assertThat(publisher.sent).extracting(RecordingPublisher.Sent::stepId).containsExactly("B", "C");
        assertThat(publisher.sent).extracting(RecordingPublisher.Sent::message).containsOnly(
                ChoreographyMessage.start("order-42", "checkout").recordStep("A", "10"));
    }

    @Test
    void aFailingStepPublishesNothing() {
        var runner = new StepRunner("A", message -> {
            throw new IllegalStateException("payment provider down");
        }, FLOW, publisher);

        assertThatThrownBy(() -> runner.handle(ChoreographyMessage.start("order-42", "checkout")))
                .hasMessageContaining("payment provider down");
        assertThat(publisher.sent).isEmpty();
        assertThat(publisher.completed).isEmpty();
    }

    @Test
    void rejectsABlankStepId() {
        assertThatThrownBy(() -> new StepRunner(" ", message -> "x", FLOW, publisher))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
