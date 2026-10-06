package com.eda.choreography.domain.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.compensation.CompensationPublisher;
import com.eda.choreography.domain.compensation.CompensationRequest;
import com.eda.choreography.domain.compensation.CompensationTrigger;
import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StepRunnerTest {

    private static final LinearFlow FLOW = LinearFlow.of("checkout", "A", "B", "C");
    private static final Map<String, Object> RESERVED = Map.of("quantity", 10);
    private static final Map<String, Object> PRICED = Map.of("amount", 30);
    private static final Map<String, Object> TAXED = Map.of("total", 33);

    private final RecordingPublisher publisher = new RecordingPublisher();
    private final RecordingCompensations compensations = new RecordingCompensations();
    private final CompensationTrigger trigger = new CompensationTrigger(compensations);

    @Test
    void runsTheStepRecordsItAndPublishesToTheNextStep() {
        var incoming = ChoreographyMessage.start("order-42", "checkout", Map.of()).recordStep("A", RESERVED);
        var runner = new StepRunner("B", message -> PRICED, FLOW, publisher, trigger);

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
                FLOW, publisher, trigger);

        tripled.handle(incoming);

        assertThat(publisher.sent.get(0).message().resultOf("B")).contains(PRICED);
    }

    @Test
    void theLastStepReportsTheInstanceAsCompleted() {
        var incoming = ChoreographyMessage.start("order-42", "checkout", Map.of())
                .recordStep("A", RESERVED)
                .recordStep("B", PRICED);
        var runner = new StepRunner("C", message -> TAXED, FLOW, publisher, trigger);

        runner.handle(incoming);

        assertThat(publisher.sent).isEmpty();
        assertThat(publisher.completed).containsExactly(incoming.recordStep("C", TAXED));
    }

    @Test
    void publishesTheSameMessageToEveryNextStep() {
        NextSteps toBoth = (stepId, message) -> List.of("B", "C");
        var runner = new StepRunner("A", message -> RESERVED, toBoth, publisher, trigger);

        runner.handle(ChoreographyMessage.start("order-42", "checkout", Map.of()));

        assertThat(publisher.sent).extracting(RecordingPublisher.Sent::stepId).containsExactly("B", "C");
        assertThat(publisher.sent).extracting(RecordingPublisher.Sent::message).containsOnly(
                ChoreographyMessage.start("order-42", "checkout", Map.of()).recordStep("A", RESERVED));
    }

    @Test
    void aFailingStepPublishesNothing() {
        var runner = new StepRunner("A", message -> {
            throw new IllegalStateException("payment provider down");
        }, FLOW, publisher, trigger);

        assertThatThrownBy(() -> runner.handle(ChoreographyMessage.start("order-42", "checkout", Map.of())))
                .hasMessageContaining("payment provider down");
        assertThat(publisher.sent).isEmpty();
        assertThat(publisher.completed).isEmpty();
    }

    @Test
    void aStepThatGivesUpOutsideAForkStartsUndoingTheInstance() {
        var incoming = ChoreographyMessage.start("order-42", "checkout", Map.of()).recordStep("A", RESERVED);
        var runner = new StepRunner("B", message -> PRICED, FLOW, publisher, trigger);

        runner.fail(incoming);

        var failed = incoming.recordFailure("B");
        var failure = failed.trace().get(1);
        assertThat(publisher.sent).isEmpty();
        assertThat(publisher.completed).isEmpty();
        assertThat(compensations.requests).singleElement().satisfies(sent -> {
            assertThat(sent.stepId()).isEqualTo("A");
            assertThat(sent.request()).isEqualTo(
                    new CompensationRequest(failure.id(), incoming.trace().get(0).id(), failure.id(), failed));
        });
    }

    @Test
    void aFirstStepThatGivesUpLeavesNothingToUndo() {
        var start = ChoreographyMessage.start("order-42", "checkout", Map.of());
        var runner = new StepRunner("A", message -> RESERVED, FLOW, publisher, trigger);

        runner.fail(start);

        assertThat(compensations.requests).isEmpty();
        assertThat(compensations.compensated).containsExactly(start.recordFailure("A"));
    }

    @Test
    void aStepThatGivesUpInsideAForkReportsTheFailureToItsJoin() {
        var forked = new NextSteps() {
            @Override
            public List<String> after(String stepId, ChoreographyMessage message) {
                return List.of("join");
            }

            @Override
            public Optional<String> enclosingJoin(String stepId) {
                return Optional.of("join");
            }
        };
        var incoming = ChoreographyMessage.start("order-42", "checkout", Map.of()).recordStep("A", RESERVED);
        var runner = new StepRunner("B", message -> PRICED, forked, publisher, trigger);

        runner.fail(incoming);

        assertThat(compensations.requests).isEmpty();
        assertThat(compensations.compensated).isEmpty();
        assertThat(publisher.sent).singleElement().satisfies(sent -> {
            assertThat(sent.stepId()).isEqualTo("join");
            assertThat(sent.message()).isEqualTo(incoming.recordFailure("B"));
        });
    }

    @Test
    void rejectsABlankStepId() {
        assertThatThrownBy(() -> new StepRunner(" ", message -> Map.of(), FLOW, publisher, trigger))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Test double: keeps what the compensation trigger sends. */
    private static final class RecordingCompensations implements CompensationPublisher {

        record Sent(String stepId, CompensationRequest request) {}

        final List<Sent> requests = new ArrayList<>();
        final List<ChoreographyMessage> compensated = new ArrayList<>();

        @Override
        public void publish(String stepId, CompensationRequest request) {
            requests.add(new Sent(stepId, request));
        }

        @Override
        public void publishCompensated(ChoreographyMessage instance) {
            compensated.add(instance);
        }
    }
}
