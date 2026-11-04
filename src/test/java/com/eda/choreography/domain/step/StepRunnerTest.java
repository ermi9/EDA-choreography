package com.eda.choreography.domain.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.compensation.CompensationRequest;
import com.eda.choreography.domain.compensation.CompensationTrigger;
import com.eda.choreography.domain.join.ForkSkips;
import com.eda.choreography.domain.join.InMemoryForkSkips;
import com.eda.choreography.domain.join.JoinKey;
import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.trace.TraceEntry;
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
            assertThat(sent.message()).isEqualTo(incoming.recordStep("B", PRICED).addressedTo("C"));
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
        var reserved = ChoreographyMessage.start("order-42", "checkout", Map.of()).recordStep("A", RESERVED);
        assertThat(publisher.sent).extracting(RecordingPublisher.Sent::message)
                .containsExactly(reserved.addressedTo("B"), reserved.addressedTo("C"));
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
    void aStepThatHasRunAsOftenAsItsFlowAllowsFailsInsteadOfRunningAgainAndTheInstanceIsUndone() {
        NextSteps loop = new NextSteps() {
            @Override
            public List<String> after(String stepId, ChoreographyMessage message) {
                return List.of(stepId.equals("A") ? "B" : "A");
            }

            @Override
            public int maxRuns(String stepId, ChoreographyMessage message) {
                return 2;
            }
        };
        var twice = ChoreographyMessage.start("order-42", "checkout", Map.of())
                .recordStep("A", RESERVED).recordStep("B", PRICED)
                .recordStep("A", RESERVED).recordStep("B", PRICED);
        var runner = new StepRunner("A", message -> {
            throw new AssertionError("a step past its limit must not run");
        }, loop, publisher, trigger);

        runner.handle(twice);

        var failed = twice.recordFailure("A");
        assertThat(publisher.sent).isEmpty();
        assertThat(compensations.requests).singleElement().satisfies(sent -> {
            assertThat(sent.stepId()).isEqualTo("B");
            assertThat(sent.request().entryId()).isEqualTo(twice.trace().get(3).id());
            assertThat(sent.request().instance()).isEqualTo(failed);
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
            assertThat(sent.message()).isEqualTo(incoming.recordFailure("B").addressedTo("join"));
        });
    }

    @Test
    void theJoinAFailureGoesToDependsOnTheFlowOfTheMessage() {
        // One service can run a step in several flows, each forking and joining differently.
        var perFlow = new NextSteps() {
            @Override
            public List<String> after(String stepId, ChoreographyMessage message) {
                return List.of();
            }

            @Override
            public Optional<String> enclosingJoin(String stepId, ChoreographyMessage message) {
                return message.flowName().equals("returns") ? Optional.of("refund-join") : Optional.empty();
            }
        };
        var incoming = ChoreographyMessage.start("order-42", "returns", Map.of()).recordStep("A", RESERVED);

        new StepRunner("B", message -> PRICED, perFlow, publisher, trigger).fail(incoming);

        assertThat(compensations.requests).isEmpty();
        assertThat(publisher.sent).singleElement().satisfies(sent -> assertThat(sent.stepId()).isEqualTo("refund-join"));
    }

    @Test
    void aServiceRunsTheStepTheMessageIsAddressedToAndHandsOnToTheServiceOfTheNextStep() {
        // "notify" runs two steps of the flow: telling the buyer, then telling the seller.
        var twice = new NextSteps() {
            @Override
            public List<String> after(String stepId, ChoreographyMessage message) {
                return stepId.equals("notify-buyer") ? List.of("notify-seller") : List.of();
            }

            @Override
            public String serviceOf(String stepId, ChoreographyMessage message) {
                return stepId.startsWith("notify-") ? "notify" : stepId;
            }
        };
        var incoming = ChoreographyMessage.start("order-42", "checkout", Map.of())
                .recordStep("A", RESERVED)
                .addressedTo("notify-buyer");

        new StepRunner("notify", message -> Map.of("to", message.step()), twice, publisher, trigger).handle(incoming);

        assertThat(publisher.sent).singleElement().satisfies(sent -> {
            assertThat(sent.stepId()).isEqualTo("notify");
            assertThat(sent.message().step()).isEqualTo("notify-seller");
            var ran = sent.message().trace().get(1);
            assertThat(ran.stepId()).isEqualTo("notify-buyer");
            assertThat(ran.service()).isEqualTo("notify");
            assertThat(sent.message().results().get(ran.resultRef())).isEqualTo(Map.of("to", "notify-buyer"));
        });
    }

    @Test
    void undoingAStepGoesToTheServiceThatRanIt() {
        var incoming = ChoreographyMessage.start("order-42", "checkout", Map.of())
                .recordStep("reserve", "stock", RESERVED)
                .addressedTo("price");

        new StepRunner("pricing", message -> PRICED, FLOW, publisher, trigger).fail(incoming);

        assertThat(compensations.requests).singleElement()
                .satisfies(sent -> assertThat(sent.stepId()).isEqualTo("stock"));
    }

    @Test
    void aStepWhoseNextServiceTakesNoWorkPublishesNothingAndSaysWhichServiceItWaitsFor() {
        var runner = new StepRunner("B", message -> PRICED, FLOW, publisher, trigger, service -> !service.equals("C"));
        var incoming = ChoreographyMessage.start("order-42", "checkout", Map.of()).recordStep("A", RESERVED);

        assertThatThrownBy(() -> runner.handle(incoming))
                .isInstanceOf(NextHopUnavailableException.class)
                .hasMessageContaining("C");
        assertThat(publisher.sent).isEmpty();
        assertThat(publisher.completed).isEmpty();
    }

    @Test
    void whenTheNextServiceNeverComesTheStepCountsAsDoneAndTheNextOneAsFailedSoTheStepIsUndone() {
        var runner = new StepRunner("B", message -> PRICED, FLOW, publisher, trigger, service -> !service.equals("C"));
        var incoming = ChoreographyMessage.start("order-42", "checkout", Map.of()).recordStep("A", RESERVED);

        runner.failNextHop(incoming);

        var failed = incoming.recordStep("B", PRICED).recordFailure("C");
        var failure = failed.trace().get(2);
        assertThat(publisher.sent).isEmpty();
        // B committed its work, so it is the first thing undone.
        assertThat(compensations.requests).singleElement().satisfies(sent -> {
            assertThat(sent.stepId()).isEqualTo("B");
            assertThat(sent.request()).isEqualTo(
                    new CompensationRequest(failure.id(), failed.trace().get(1).id(), failure.id(), failed));
        });
    }

    @Test
    void whenOneBranchOfAForkNeverGetsItsServiceTheOthersGoOnAndTheJoinHearsOfTheFailure() {
        var fork = new NextSteps() {
            @Override
            public List<String> after(String stepId, ChoreographyMessage message) {
                return stepId.equals("A") ? List.of("B", "C") : List.of("J");
            }

            @Override
            public Optional<String> enclosingJoin(String stepId) {
                return stepId.equals("B") || stepId.equals("C") ? Optional.of("J") : Optional.empty();
            }
        };
        var runner = new StepRunner("A", message -> RESERVED, fork, publisher, trigger, service -> !service.equals("C"));
        var start = ChoreographyMessage.start("order-42", "checkout", Map.of());

        runner.failNextHop(start);

        var reserved = start.recordStep("A", RESERVED);
        assertThat(compensations.requests).isEmpty();
        assertThat(publisher.sent).extracting(RecordingPublisher.Sent::stepId).containsExactly("B", "J");
        assertThat(publisher.sent.get(0).message()).isEqualTo(reserved.addressedTo("B"));
        assertThat(publisher.sent.get(1).message()).isEqualTo(reserved.recordFailure("C").addressedTo("J"));
    }

    // Steps inside a fork stop once a sibling branch has failed.

    /** A forks into B and C; B hands on to B2; B2 and C end at the join J. */
    private static final NextSteps FORK = new NextSteps() {
        @Override
        public List<String> after(String stepId, ChoreographyMessage message) {
            return switch (stepId) {
                case "A" -> List.of("B", "C");
                case "B" -> List.of("B2");
                default -> List.of("J");
            };
        }

        @Override
        public Optional<String> enclosingJoin(String stepId) {
            return List.of("B", "B2", "C").contains(stepId) ? Optional.of("J") : Optional.empty();
        }
    };
    private static final JoinKey FORK_KEY = new JoinKey("order-42", "J");
    private static final ChoreographyMessage AT_B2 = ChoreographyMessage.start("order-42", "checkout", Map.of())
            .recordStep("A", RESERVED)
            .recordStep("B", PRICED)
            .addressedTo("B2");

    private final InMemoryForkSkips forks = new InMemoryForkSkips();

    @Test
    void aStepOfAForkThatHasFailedDoesNoWorkAndGoesStraightToTheJoinAsSkipped() {
        forks.markFailed(FORK_KEY);
        var runner = forkStep("B2", message -> {
            throw new AssertionError("a skipped step must not do its work");
        });

        runner.handle(AT_B2);

        assertThat(compensations.requests).isEmpty();
        assertThat(publisher.sent).singleElement().satisfies(sent -> {
            assertThat(sent.stepId()).isEqualTo("J");
            assertThat(sent.message()).isEqualTo(AT_B2.recordSkip("B2", "B2").addressedTo("J"));
        });
    }

    @Test
    void aStepOfAForkNoBranchHasFailedInRunsAndHandsOn() {
        forkStep("B2", message -> TAXED).handle(AT_B2);

        assertThat(publisher.sent).singleElement()
                .satisfies(sent -> assertThat(sent.message()).isEqualTo(AT_B2.recordStep("B2", TAXED).addressedTo("J")));
    }

    @Test
    void aStepOutsideAnyForkNeverAsksAboutForks() {
        var unreachable = new ForkSkips() {
            @Override
            public void markFailed(JoinKey fork) {
                throw new AssertionError("no fork to mark");
            }

            @Override
            public boolean skips(JoinKey fork, String entryId) {
                throw new AssertionError("no fork to ask about");
            }
        };
        var incoming = ChoreographyMessage.start("order-42", "checkout", Map.of()).recordStep("A", RESERVED);

        new StepRunner("B", message -> PRICED, FLOW, publisher, trigger, service -> true, unreachable).handle(incoming);
        new StepRunner("B", message -> PRICED, FLOW, publisher, trigger, service -> true, unreachable).fail(incoming);

        assertThat(publisher.sent).hasSize(1);
        assertThat(compensations.requests).hasSize(1);
    }

    @Test
    void aStepThatGivesUpInsideAForkMarksTheForkFailedBeforeTellingTheJoin() {
        var markedWhenPublished = new ArrayList<Boolean>();
        MessagePublisher checking = new MessagePublisher() {
            @Override
            public void publish(String stepId, ChoreographyMessage message) {
                markedWhenPublished.add(forks.skips(FORK_KEY, "a sibling's next step"));
            }

            @Override
            public void publishCompleted(ChoreographyMessage message) {}
        };

        new StepRunner("B2", message -> TAXED, FORK, checking, trigger, service -> true, forks).fail(AT_B2);

        assertThat(markedWhenPublished).containsExactly(true);
    }

    @Test
    void aStepWhoseNextServiceNeverComesInsideAForkMarksTheFork() {
        var atB = ChoreographyMessage.start("order-42", "checkout", Map.of()).recordStep("A", RESERVED).addressedTo("B");

        new StepRunner("B", message -> PRICED, FORK, publisher, trigger, service -> !service.equals("B2"), forks)
                .failNextHop(atB);

        assertThat(forks.skips(FORK_KEY, "C:not-yet-run")).isTrue();
        assertThat(publisher.sent).singleElement().satisfies(sent -> assertThat(sent.stepId()).isEqualTo("J"));
    }

    @Test
    void aMessageRedeliveredAfterItsStepWasToldToRunRunsAgainThoughTheForkFailedMeanwhile() {
        // The first delivery may have done the work and crashed before handing on: skipping now
        // would leave that work undone forever.
        var runner = forkStep("B2", message -> TAXED);
        runner.handle(AT_B2);
        forks.markFailed(FORK_KEY);

        runner.handle(AT_B2);

        assertThat(publisher.sent).hasSize(2);
        assertThat(publisher.sent.get(1)).isEqualTo(publisher.sent.get(0));
    }

    @Test
    void aStepThatWasSkippedIsSkippedAgainWhenItsMessageIsRedelivered() {
        forks.markFailed(FORK_KEY);
        var runner = forkStep("B2", message -> TAXED);
        runner.handle(AT_B2);

        runner.handle(AT_B2);

        assertThat(publisher.sent).extracting(sent -> sent.message().trace().get(2).outcome())
                .containsExactly(TraceEntry.Outcome.SKIPPED, TraceEntry.Outcome.SKIPPED);
    }

    private StepRunner forkStep(String stepId, StepAction action) {
        return new StepRunner(stepId, action, FORK, publisher, trigger, service -> true, forks);
    }

    @Test
    void rejectsABlankStepId() {
        assertThatThrownBy(() -> new StepRunner(" ", message -> Map.of(), FLOW, publisher, trigger))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
