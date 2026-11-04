package com.eda.choreography.domain.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.compensation.CompensationTrigger;
import com.eda.choreography.domain.join.InMemoryForkSkips;
import com.eda.choreography.domain.join.InMemoryJoinDeadlines;
import com.eda.choreography.domain.join.InMemoryJoinStateStore;
import com.eda.choreography.domain.join.JoinKey;
import com.eda.choreography.domain.join.JoinStateMachine;
import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.trace.TraceEntry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** A fork from A into B and C, joined at J, which hands on to D. */
class JoinRunnerTest {

    private static final ChoreographyMessage FORKED =
            ChoreographyMessage.start("order-42", "checkout", Map.of()).recordStep("A", Map.of("quantity", 10));
    private static final ChoreographyMessage FROM_B = FORKED.recordStep("B", Map.of("amount", 30));
    private static final ChoreographyMessage FROM_C = FORKED.recordStep("C", Map.of("slot", "monday"));
    private static final Map<String, Object> JOINED = Map.of("ready", true);
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final JoinKey KEY = new JoinKey("order-42", "J");

    private final RecordingPublisher publisher = new RecordingPublisher();
    private final RecordingCompensations compensations = new RecordingCompensations();
    private final CompensationTrigger trigger = new CompensationTrigger(compensations);
    private final InMemoryJoinDeadlines deadlines = new InMemoryJoinDeadlines();
    private final JoinRunner join = joinPublishingTo(publisher, deadlines);

    @Test
    void runsTheStepOnceEveryBranchHasArrived() {
        join.handle(FROM_B);
        assertThat(publisher.sent).isEmpty();

        join.handle(FROM_C);

        var merged = ChoreographyMessage.merge(List.of(FROM_B, FROM_C));
        assertThat(publisher.sent).singleElement().satisfies(sent -> {
            assertThat(sent.stepId()).isEqualTo("D");
            assertThat(sent.message()).isEqualTo(merged.recordStep("J", JOINED).addressedTo("D"));
        });
        assertThat(compensations.requests).isEmpty();
    }

    @Test
    void theJoinedMessageDoesNotDependOnWhichBranchArrivedFirst() {
        // A ran twice before the fork and answered differently, so the branches disagree on its result.
        var fromC = ChoreographyMessage.start("order-42", "checkout", Map.of())
                .recordStep("A", Map.of("quantity", 11))
                .recordStep("C", Map.of("slot", "monday"));

        assertThat(joined(FROM_B, fromC)).isEqualTo(joined(fromC, FROM_B));
    }

    @Test
    void aBranchDeliveredTwiceBeforeTheJoinFiresIsCountedOnce() {
        join.handle(FROM_B);
        join.handle(FROM_B);

        assertThat(publisher.sent).isEmpty();
    }

    @Test
    void aBranchRedeliveredAfterTheJoinFiredRunsTheStepAgainWithTheSameResult() {
        // The first run may have crashed after the join fired but before handing on.
        join.handle(FROM_B);
        join.handle(FROM_C);

        join.handle(FROM_C);

        assertThat(publisher.sent).hasSize(2);
        assertThat(publisher.sent.get(1)).isEqualTo(publisher.sent.get(0));
    }

    @Test
    void theFirstBranchStartsTheWaitAndALaterOneDoesNotExtendIt() {
        join.handle(FROM_B);

        assertThat(deadlines.dueBy(NOW.plus(TIMEOUT).minusMillis(1), 10)).isEmpty();
        assertThat(deadlines.dueBy(NOW.plus(TIMEOUT), 10)).containsExactly(KEY);
    }

    @Test
    void aJoinThatFiredHasNoDeadline() {
        join.handle(FROM_B);
        join.handle(FROM_C);

        assertThat(deadlines.dueBy(NOW.plus(TIMEOUT), 10)).isEmpty();
    }

    @Test
    void aRedeliveredBranchSetsADeadlineTheFirstDeliveryDidNotGetTo() {
        join.handle(FROM_B);
        deadlines.remove(KEY);

        join.handle(FROM_B);

        assertThat(deadlines.dueBy(NOW.plus(TIMEOUT), 10)).containsExactly(KEY);
    }

    @Test
    void aFailedBranchWaitsForTheOthersAndThenUndoesThemAll() {
        var failedB = FORKED.recordFailure("B");
        join.handle(failedB);
        assertThat(compensations.requests).isEmpty();

        join.handle(FROM_C);

        assertThat(publisher.sent).isEmpty();
        assertThat(compensations.requests).singleElement().satisfies(sent -> {
            assertThat(sent.stepId()).isEqualTo("C");
            assertThat(sent.request().runId()).isEqualTo("join-failed:J");
            assertThat(sent.request().instance()).isEqualTo(ChoreographyMessage.merge(List.of(failedB, FROM_C)));
        });
    }

    @Test
    void aJoinStepThatGivesUpUndoesEveryBranch() {
        join.handle(FROM_B);
        join.handle(FROM_C);
        publisher.sent.clear();

        join.fail(FROM_C);

        var failed = ChoreographyMessage.merge(List.of(FROM_B, FROM_C)).recordFailure("J");
        var failure = failed.trace().stream().filter(entry -> entry.stepId().equals("J")).findFirst().orElseThrow();
        assertThat(publisher.sent).isEmpty();
        assertThat(compensations.requests).extracting(RecordingCompensations.Sent::stepId)
                .containsExactlyInAnyOrder("B", "C");
        assertThat(compensations.requests).allSatisfy(sent -> {
            assertThat(sent.request().runId()).isEqualTo(failure.id());
            assertThat(sent.request().instance()).isEqualTo(failed);
        });
    }

    @Test
    void givingUpBeforeTheJoinFiredStillCountsTheBranch() {
        join.fail(FROM_B);
        join.handle(FROM_C);

        assertThat(publisher.sent).singleElement().satisfies(sent -> assertThat(sent.stepId()).isEqualTo("D"));
        assertThat(compensations.requests).isEmpty();
    }

    @Test
    void aTimeoutUndoesTheBranchesThatArrived() {
        join.handle(FROM_B);

        join.timeOut("order-42");

        assertThat(publisher.sent).isEmpty();
        assertThat(compensations.requests).singleElement().satisfies(sent -> {
            assertThat(sent.stepId()).isEqualTo("B");
            assertThat(sent.request().runId()).isEqualTo("join-timeout:J");
            assertThat(sent.request().instance()).isEqualTo(FROM_B);
        });
    }

    @Test
    void aBranchThatArrivesAfterTheTimeoutUndoesOnlyWhatTheTimeoutDidNot() {
        join.handle(FROM_B);
        join.timeOut("order-42");
        compensations.requests.clear();

        join.handle(FROM_C);

        var cEntry = FROM_C.trace().get(1);
        var undoneByTimeout = FROM_B.trace().stream().map(TraceEntry::id).collect(Collectors.toSet());
        assertThat(publisher.sent).isEmpty();
        assertThat(compensations.requests).singleElement().satisfies(sent -> {
            assertThat(sent.stepId()).isEqualTo("C");
            assertThat(sent.request().runId()).isEqualTo("join-late:" + cEntry.id());
            assertThat(sent.request().instance()).isEqualTo(FROM_C);
            assertThat(sent.request().alreadyUndone()).isEqualTo(undoneByTimeout);
        });
    }

    @Test
    void aTimeoutAfterTheJoinFiredChangesNothing() {
        join.handle(FROM_B);
        join.handle(FROM_C);

        join.timeOut("order-42");

        assertThat(compensations.requests).isEmpty();
    }

    @Test
    void aTimeoutForAJoinNoBranchReachedChangesNothing() {
        join.timeOut("order-42");

        assertThat(compensations.requests).isEmpty();
        assertThat(compensations.compensated).isEmpty();
    }

    @Test
    void rejectsAMessageThatDoesNotEndInOneBranch() {
        var unmerged = ChoreographyMessage.merge(List.of(FROM_B, FROM_C));

        assertThatThrownBy(() -> join.handle(unmerged)).isInstanceOf(IllegalArgumentException.class);
    }

    // Forks whose branches stop early once one of them has failed.

    @Test
    void aBranchThatReachesTheJoinTwiceEndingDifferentlyCountsOnce() {
        // B ran, but a redelivered copy of its message found the fork failed and skipped it.
        var forkAware = forkAwareJoin();
        forkAware.handle(FROM_B);
        forkAware.handle(FORKED.recordSkip("B", "B"));
        assertThat(publisher.sent).isEmpty();
        assertThat(compensations.requests).isEmpty();

        forkAware.handle(FROM_C);

        assertThat(compensations.requests).extracting(RecordingCompensations.Sent::stepId)
                .containsExactlyInAnyOrder("B", "C");
    }

    @Test
    void aBranchIsKnownByItsFirstStepEvenWhenALoopRanThatStepAgain() {
        var forkAware = forkAwareJoin();
        var twice = FORKED.recordStep("B", Map.of("round", 1)).recordStep("B", Map.of("round", 2));
        forkAware.handle(twice);

        forkAware.handle(FORKED.recordStep("B", Map.of("round", 1)).recordSkip("B", "B"));

        assertThat(compensations.requests).isEmpty();
        assertThat(publisher.sent).isEmpty();
    }

    @Test
    void aSkippedBranchMakesTheJoinUndoTheInstanceEvenWhenNoStepFailed() {
        // The step that marked the fork failed was redelivered and then succeeded.
        var forkAware = forkAwareJoin();
        var skippedC = FORKED.recordSkip("C", "C");
        forkAware.handle(FROM_B);

        forkAware.handle(skippedC);

        assertThat(publisher.sent).isEmpty();
        assertThat(compensations.requests).singleElement().satisfies(sent -> {
            assertThat(sent.stepId()).isEqualTo("B");
            assertThat(sent.request().runId()).isEqualTo("join-failed:J");
            assertThat(sent.request().instance()).isEqualTo(ChoreographyMessage.merge(List.of(FROM_B, skippedC)));
        });
    }

    @Test
    void aCopyThatArrivesAfterTheJoinFiredUndoesOnlyTheStepsTheJoinNeverSaw() {
        var forkAware = forkAwareJoin();
        var skippedB = FORKED.recordSkip("B", "B");
        forkAware.handle(skippedB);
        forkAware.handle(FROM_C);
        compensations.requests.clear();

        forkAware.handle(FROM_B);

        var bEntry = FROM_B.trace().get(1);
        var seen = ChoreographyMessage.merge(List.of(skippedB, FROM_C)).trace().stream()
                .map(TraceEntry::id).collect(Collectors.toSet());
        assertThat(compensations.requests).filteredOn(sent -> sent.request().runId().equals("join-copy:" + bEntry.id()))
                .singleElement().satisfies(sent -> {
                    assertThat(sent.stepId()).isEqualTo("B");
                    assertThat(sent.request().entryId()).isEqualTo(bEntry.id());
                    assertThat(sent.request().instance()).isEqualTo(FROM_B);
                    assertThat(sent.request().alreadyUndone()).isEqualTo(seen);
                });
        assertThat(publisher.sent).isEmpty();
    }

    @Test
    void aCopyOfABranchBeforeTheJoinFiresIsMergedSoItsWorkIsUndoneWithTheRest() {
        var forkAware = forkAwareJoin();
        var reachedB = FORKED.recordStep("B", Map.of("amount", 30));
        forkAware.handle(reachedB.recordSkip("B2", "B2"));
        forkAware.handle(reachedB.recordStep("B2", Map.of("receipt", 7)));

        forkAware.handle(FROM_C);

        var b2 = reachedB.recordStep("B2", Map.of("receipt", 7)).trace().get(2);
        assertThat(compensations.requests).extracting(sent -> sent.request().entryId())
                .containsExactlyInAnyOrder(b2.id(), FROM_C.trace().get(1).id());
    }

    @Test
    void aFailedBranchMarksTheForkFailedInCaseItsOwnStepCouldNot() {
        forkAwareJoin().handle(FORKED.recordFailure("B"));

        assertThat(forks.skips(KEY, "C:not-yet-run")).isTrue();
    }

    @Test
    void aTimeoutMarksTheForkFailedSoBranchesStillOnTheirWayAreSkipped() {
        var forkAware = forkAwareJoin();
        forkAware.handle(FROM_B);

        forkAware.timeOut("order-42");

        assertThat(forks.skips(KEY, "C:not-yet-run")).isTrue();
    }

    @Test
    void aJoinThatFiredWithoutFailureLeavesTheForkUnmarked() {
        var forkAware = forkAwareJoin();
        forkAware.handle(FROM_B);
        forkAware.handle(FROM_C);
        forkAware.timeOut("order-42");

        assertThat(forks.skips(KEY, "C:not-yet-run")).isFalse();
    }

    private final InMemoryForkSkips forks = new InMemoryForkSkips();

    /** A join that knows from its routing which steps are inside its fork, and shares the fork's failure. */
    private JoinRunner forkAwareJoin() {
        var routes = new NextSteps() {
            @Override
            public List<String> after(String stepId, ChoreographyMessage message) {
                return List.of("D");
            }

            @Override
            public Optional<String> enclosingJoin(String stepId) {
                return List.of("B", "B2", "C").contains(stepId) ? Optional.of("J") : Optional.empty();
            }
        };
        return new JoinRunner(
                new StepRunner("J", message -> JOINED, routes, publisher, trigger, service -> true, forks),
                2, TIMEOUT, new JoinStateMachine(new InMemoryJoinStateStore()), deadlines, trigger,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** What a fresh join hands on after the two branches arrive in the given order. */
    private ChoreographyMessage joined(ChoreographyMessage first, ChoreographyMessage second) {
        var sent = new RecordingPublisher();
        var fresh = joinPublishingTo(sent, new InMemoryJoinDeadlines());
        fresh.handle(first);
        fresh.handle(second);
        return sent.sent.get(0).message();
    }

    @Test
    void aJoinServingSeveralFlowsWaitsAsEachFlowSays() {
        // "returns" forks three ways and waits a minute; "checkout" forks two ways and waits five.
        var waits = (JoinWaits) (flowName, joinStep) -> flowName.equals("returns")
                ? new JoinWait(3, Duration.ofMinutes(1))
                : new JoinWait(2, TIMEOUT);
        var shared = new JoinRunner(
                new StepRunner("J", message -> JOINED, (stepId, message) -> List.of("D"), publisher, trigger),
                waits, new JoinStateMachine(new InMemoryJoinStateStore()), deadlines, trigger,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var forked = ChoreographyMessage.start("return-7", "returns", Map.of()).recordStep("A", Map.of());

        shared.handle(forked.recordStep("B", Map.of()));
        shared.handle(forked.recordStep("C", Map.of()));
        assertThat(publisher.sent).isEmpty();
        assertThat(deadlines.dueBy(NOW.plus(Duration.ofMinutes(1)), 10)).containsExactly(new JoinKey("return-7", "J"));

        shared.handle(forked.recordStep("E", Map.of()));
        shared.handle(FROM_B);
        shared.handle(FROM_C);

        assertThat(publisher.sent).extracting(sent -> sent.message().correlationId())
                .containsExactly("return-7", "order-42");
    }

    private JoinRunner joinPublishingTo(RecordingPublisher sent, InMemoryJoinDeadlines deadlinesOfJoin) {
        return new JoinRunner(
                new StepRunner("J", message -> JOINED, (stepId, message) -> List.of("D"), sent, trigger),
                2, TIMEOUT, new JoinStateMachine(new InMemoryJoinStateStore()), deadlinesOfJoin, trigger,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
