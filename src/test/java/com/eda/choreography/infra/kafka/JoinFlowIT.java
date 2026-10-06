package com.eda.choreography.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.join.JoinKey;
import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.trace.TraceEntry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Joins over the real broker, with their state in the real Redis. */
class JoinFlowIT extends AbstractFlowIT {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final Duration LONG_WAIT = Duration.ofMinutes(1);

    @Test
    void aForkJoinsAndCompletesWithEveryBranchsResult() throws Exception {
        var a = unique("a");
        var b = unique("b");
        var c = unique("c");
        var join = unique("join");
        createTopicsFor(a, b, c, join);
        var routes = new Routes().then(a, b, c).then(b, join).then(c, join).joinedAt(join, b, c);
        startStep(a, message -> Map.of("quantity", 10), routes);
        startStep(b, message -> Map.of("amount", 30), routes);
        startStep(c, message -> Map.of("slot", "monday"), routes);
        startJoin(join, message -> Map.of("ready", true), routes, 2, LONG_WAIT);
        var correlationId = newInstance();

        try (var outcomes = outcomes()) {
            publisher.publish(a, ChoreographyMessage.start(correlationId, "checkout", Map.of()));

            var done = await(outcomes, completedTopic, correlationId, WAIT);

            assertThat(done.resultOf(b)).contains(Map.of("amount", 30));
            assertThat(done.resultOf(c)).contains(Map.of("slot", "monday"));
            assertThat(done.resultOf(join)).contains(Map.of("ready", true));
            assertThat(entry(done, join).parents()).containsExactlyInAnyOrder(entry(done, b).id(), entry(done, c).id());
            assertThat(deadlines.dueBy(Instant.now().plus(LONG_WAIT), 1000)).doesNotContain(new JoinKey(correlationId, join));
        }
    }

    @Test
    void aJoinThatWaitsTooLongUndoesWhatArrivedAndALateBranchUndoesItself() throws Exception {
        var a = unique("a");
        var b = unique("b");
        var c = unique("c");
        var join = unique("join");
        createTopicsFor(a, b, c, join);
        var routes = new Routes().then(a, b, c).then(b, join).then(c, join).joinedAt(join, b, c);
        startStep(a, message -> Map.of("quantity", 10), routes);
        startStep(b, message -> Map.of("amount", 30), routes);
        // c is not running yet, so its branch never reaches the join in time.
        startJoin(join, message -> Map.of("ready", true), routes, 2, Duration.ofSeconds(2));
        var correlationId = newInstance();

        try (var outcomes = outcomes()) {
            publisher.publish(a, ChoreographyMessage.start(correlationId, "checkout", Map.of()));

            var timedOut = await(outcomes, compensatedTopic, correlationId, WAIT);
            assertThat(undone).containsExactly(b, a);
            assertThat(timedOut.trace()).extracting(TraceEntry::stepId).containsExactlyInAnyOrder(a, b);

            startStep(c, message -> Map.of("slot", "monday"), routes);
            var late = await(outcomes, compensatedTopic, correlationId, WAIT);

            assertThat(late.trace()).extracting(TraceEntry::stepId).containsExactlyInAnyOrder(a, c);
            assertThat(undone).containsExactly(b, a, c, a);
            assertThat(more(outcomes, completedTopic, List.of(correlationId), Duration.ofSeconds(1))).isEmpty();
        }
    }

    @Test
    void aJoinKeepsItsBranchesAcrossARestartOfItsService() throws Exception {
        var join = unique("join");
        createTopicsFor(join);
        var routes = new Routes();
        var correlationId = newInstance();
        var forked = ChoreographyMessage.start(correlationId, "checkout", Map.of()).recordStep("a", Map.of("quantity", 10));
        var first = startJoin(join, message -> Map.of("ready", true), routes, 2, LONG_WAIT);

        try (var outcomes = outcomes()) {
            publisher.publish(join, forked.recordStep("b", Map.of("amount", 30)));
            awaitArrivals(new JoinKey(correlationId, join), 1);
            first.stop();

            publisher.publish(join, forked.recordStep("c", Map.of("slot", "monday")));
            start(containers.create(joinRunner(join, message -> Map.of("ready", true), routes, 2, LONG_WAIT)));
            var done = await(outcomes, completedTopic, correlationId, WAIT);

            assertThat(done.resultOf("b")).contains(Map.of("amount", 30));
            assertThat(done.resultOf("c")).contains(Map.of("slot", "monday"));
            assertThat(done.resultOf(join)).contains(Map.of("ready", true));
        }
    }

    @Test
    void manyBranchesArrivingAtOnceFireEachJoinExactlyOnce() throws Exception {
        int instances = 20;
        int branches = 8;
        var join = unique("join");
        createTopicsFor(join);
        var runs = new ConcurrentHashMap<String, AtomicInteger>();
        var container = containers.create(joinRunner(join, message -> {
            runs.computeIfAbsent(message.correlationId(), id -> new AtomicInteger()).incrementAndGet();
            return Map.of("ready", true);
        }, new Routes(), branches, LONG_WAIT));
        container.setConcurrency(PARTITIONS);
        start(container);
        var correlationIds = IntStream.range(0, instances).mapToObj(i -> newInstance()).toList();
        var arrivals = new ArrayList<ChoreographyMessage>();
        for (var correlationId : correlationIds) {
            var forked = ChoreographyMessage.start(correlationId, "checkout", Map.of()).recordStep("a", Map.of());
            for (int i = 0; i < branches; i++) {
                arrivals.add(forked.recordStep("branch-" + i, Map.of("n", i)));
            }
        }

        var senders = Executors.newFixedThreadPool(8);
        try (var outcomes = outcomes()) {
            for (var arrival : arrivals) {
                senders.submit(() -> publisher.publish(join, arrival));
            }
            senders.shutdown();

            var done = collect(outcomes, completedTopic, correlationIds, instances, WAIT);
            done.addAll(more(outcomes, completedTopic, correlationIds, Duration.ofSeconds(2)));

            assertThat(done).extracting(ChoreographyMessage::correlationId).containsExactlyInAnyOrderElementsOf(correlationIds);
            assertThat(done).allSatisfy(message ->
                    assertThat(entry(message, join).parents()).hasSize(branches));
            assertThat(runs.keySet()).isEqualTo(new HashSet<>(correlationIds));
            assertThat(runs.values()).allSatisfy(count -> assertThat(count).hasValue(1));
        } finally {
            senders.shutdownNow();
        }
    }

    private void awaitArrivals(JoinKey key, int count) throws InterruptedException {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            var state = joinStore.find(key);
            if (state.isPresent() && state.get().arrivedBranches().size() == count) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("join " + key + " did not get " + count + " branches within " + WAIT);
    }

    private static TraceEntry entry(ChoreographyMessage message, String stepId) {
        return message.trace().stream().filter(e -> e.stepId().equals(stepId)).findFirst().orElseThrow();
    }
}
