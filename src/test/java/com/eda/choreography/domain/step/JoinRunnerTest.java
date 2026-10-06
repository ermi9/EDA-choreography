package com.eda.choreography.domain.step;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.compensation.CompensationTrigger;
import com.eda.choreography.domain.join.InMemoryJoinDeadlines;
import com.eda.choreography.domain.join.InMemoryJoinStateStore;
import com.eda.choreography.domain.join.JoinKey;
import com.eda.choreography.domain.join.JoinStateMachine;
import com.eda.choreography.domain.message.ChoreographyMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
            assertThat(sent.message()).isEqualTo(merged.recordStep("J", JOINED));
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
    void aBranchThatArrivesAfterTheTimeoutUndoesItself() {
        join.handle(FROM_B);
        join.timeOut("order-42");
        compensations.requests.clear();

        join.handle(FROM_C);

        var cEntry = FROM_C.trace().get(1);
        assertThat(publisher.sent).isEmpty();
        assertThat(compensations.requests).singleElement().satisfies(sent -> {
            assertThat(sent.stepId()).isEqualTo("C");
            assertThat(sent.request().runId()).isEqualTo("join-late:" + cEntry.id());
            assertThat(sent.request().instance()).isEqualTo(FROM_C);
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

    /** What a fresh join hands on after the two branches arrive in the given order. */
    private ChoreographyMessage joined(ChoreographyMessage first, ChoreographyMessage second) {
        var sent = new RecordingPublisher();
        var fresh = joinPublishingTo(sent, new InMemoryJoinDeadlines());
        fresh.handle(first);
        fresh.handle(second);
        return sent.sent.get(0).message();
    }

    private JoinRunner joinPublishingTo(RecordingPublisher sent, InMemoryJoinDeadlines deadlinesOfJoin) {
        return new JoinRunner(
                new StepRunner("J", message -> JOINED, (stepId, message) -> List.of("D"), sent, trigger),
                2, TIMEOUT, new JoinStateMachine(new InMemoryJoinStateStore()), deadlinesOfJoin, trigger,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
