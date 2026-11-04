package com.eda.choreography.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.plan.Conditions;
import com.eda.choreography.domain.plan.PlanModel;
import com.eda.choreography.domain.plan.PlanNode.Split;
import com.eda.choreography.domain.plan.PlannedRoutes;
import com.eda.choreography.domain.plan.Plans;
import com.eda.choreography.domain.step.StepAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Measures the work a fork wastes after one of its branches has failed (issue #1), over the real
 * broker and Redis. Not part of {@code mvn verify}: it is named so that the integration-test run
 * leaves it out, and is run on its own with
 *
 * <pre>mvn verify -Dit.test=ForkFailureBenchmark -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false</pre>
 *
 * <p>The scenario: {@code start} forks into branch A, whose only step always fails, and branch B,
 * a chain of N steps that each take {@code benchmark.stepMillis} (1 s by default) before the
 * join. A step that throws is retried twice, a second apart, so A gives up about two seconds in,
 * while B is still working. For each run it records:
 * <ul>
 *   <li><b>B steps run</b>: B's steps whose work started at all;</li>
 *   <li><b>wasted steps</b>: B's steps whose work started after A had given up, when the instance
 *       was already bound to be undone;</li>
 *   <li><b>wasted undos</b>: the undos those wasted steps caused;</li>
 *   <li><b>B steps skipped</b>: B's steps recorded as skipped instead of run;</li>
 *   <li><b>failure to compensated</b>: from A giving up (its last attempt) to the instance reaching
 *       the compensated topic.</li>
 * </ul>
 * It only uses what the engine had before fork skipping existed, so the same file measures the
 * code before and after the change. Results go to the console and to
 * {@code target/benchmarks/fork-failure-<benchmark.label>.csv}.
 */
class ForkFailureBenchmark extends AbstractFlowIT {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired
    Conditions conditions;

    /** When each step's work started, per instance, in milliseconds since the instance started. */
    private final Map<String, Queue<Started>> starts = new ConcurrentHashMap<>();

    /** The steps undone, per instance. */
    private final Map<String, Queue<String>> undos = new ConcurrentHashMap<>();

    private record Started(String step, long atMillis) {}

    /** One measured run. */
    record Run(int branchLength, int repetition, int bStepsRun, int wastedSteps, int wastedUndos,
               int bStepsSkipped, long failureToCompensatedMillis) {}

    @Test
    void measureWorkWastedAfterAFailureInsideAFork() throws Exception {
        var lengths = Arrays.stream(System.getProperty("benchmark.branchLengths", "1,3,5").split(","))
                .map(String::strip).mapToInt(Integer::parseInt).toArray();
        int repetitions = Integer.getInteger("benchmark.repetitions", 3);
        long stepMillis = Long.getLong("benchmark.stepMillis", 1_000);
        var label = System.getProperty("benchmark.label", "current");

        var runs = new ArrayList<Run>();
        for (int length : lengths) {
            runs.addAll(measure(length, repetitions, stepMillis));
        }

        report(label, stepMillis, runs);
        assertThat(runs).hasSize(lengths.length * repetitions);
    }

    private List<Run> measure(int length, int repetitions, long stepMillis) throws Exception {
        var start = unique("start");
        var a = unique("a");
        var join = unique("join");
        var bSteps = new ArrayList<String>();
        for (int i = 1; i <= length; i++) {
            bSteps.add(unique("b" + i));
        }
        var all = new ArrayList<>(List.of(start, a, join));
        all.addAll(bSteps);
        createTopicsFor(all.toArray(String[]::new));

        var plan = PlanModel.builder("fork-failure")
                .task(start, Split.PARALLEL)
                .task(a)
                .join(join);
        bSteps.forEach(plan::task);
        plan.edge(start, a).edge(a, join).edge(start, bSteps.get(0));
        for (int i = 1; i < length; i++) {
            plan.edge(bSteps.get(i - 1), bSteps.get(i));
        }
        var model = plan.edge(bSteps.get(length - 1), join).joinTimeout(Duration.ofMinutes(1)).build();
        var routes = new PlannedRoutes(Plans.of(model), conditions);

        var began = new ConcurrentHashMap<String, Long>();
        startMeasured(start, routes, began, message -> Map.of());
        startMeasured(a, routes, began, message -> {
            throw new IllegalStateException("branch A always fails");
        });
        for (var step : bSteps) {
            startMeasured(step, routes, began, message -> {
                sleep(stepMillis);
                return Map.of("done", true);
            });
        }
        startJoin(join, message -> Map.of("joined", true), routes, 2, Duration.ofMinutes(1));

        var runs = new ArrayList<Run>();
        try (var outcomes = outcomes()) {
            for (int repetition = 1; repetition <= repetitions; repetition++) {
                var correlationId = newInstance();
                began.put(correlationId, System.currentTimeMillis());
                publisher.publish(start, ChoreographyMessage.start(correlationId, "fork-failure", Map.of()));
                var compensated = await(outcomes, compensatedTopic, correlationId, WAIT);
                var compensatedAt = System.currentTimeMillis() - began.get(correlationId);
                // The undos may still be landing after the instance was reported compensated.
                sleep(500);
                runs.add(runOf(length, repetition, correlationId, compensated, compensatedAt, a, bSteps));
            }
        }
        return runs;
    }

    private void startMeasured(String step, PlannedRoutes routes, Map<String, Long> began,
                               StepAction action) {
        startStep(step, message -> {
            var t0 = began.get(message.correlationId());
            starts.computeIfAbsent(message.correlationId(), id -> new ConcurrentLinkedQueue<>())
                    .add(new Started(step, System.currentTimeMillis() - t0));
            return action.execute(message);
        }, routes, (entry, instance) -> undos
                .computeIfAbsent(instance.correlationId(), id -> new ConcurrentLinkedQueue<>()).add(entry.stepId()));
    }

    private Run runOf(int length, int repetition, String correlationId, ChoreographyMessage compensated,
                      long compensatedAt, String a, List<String> bSteps) {
        var started = new ArrayList<>(starts.getOrDefault(correlationId, new ConcurrentLinkedQueue<>()));
        long failedAt = started.stream().filter(s -> s.step().equals(a)).mapToLong(Started::atMillis).max()
                .orElseThrow(() -> new AssertionError("branch A never ran"));
        var bStarted = started.stream().filter(s -> bSteps.contains(s.step())).toList();
        var wasted = bStarted.stream().filter(s -> s.atMillis() >= failedAt).map(Started::step).toList();
        var undone = new ArrayList<>(undos.getOrDefault(correlationId, new ConcurrentLinkedQueue<>()));
        int wastedUndos = (int) undone.stream().filter(wasted::contains).count();
        int skipped = (int) compensated.trace().stream()
                .filter(entry -> bSteps.contains(entry.stepId()) && entry.outcome().name().equals("SKIPPED"))
                .count();
        return new Run(length, repetition, bStarted.size(), wasted.size(), wastedUndos, skipped,
                compensatedAt - failedAt);
    }

    private static void report(String label, long stepMillis, List<Run> runs) throws Exception {
        var csv = new StringBuilder("label,branchLength,repetition,bStepsRun,wastedSteps,wastedUndos,"
                + "bStepsSkipped,failureToCompensatedMillis\n");
        runs.forEach(run -> csv.append(String.join(",", label, String.valueOf(run.branchLength()),
                String.valueOf(run.repetition()), String.valueOf(run.bStepsRun()), String.valueOf(run.wastedSteps()),
                String.valueOf(run.wastedUndos()), String.valueOf(run.bStepsSkipped()),
                String.valueOf(run.failureToCompensatedMillis()))).append('\n'));
        var out = Path.of("target", "benchmarks", "fork-failure-" + label + ".csv");
        Files.createDirectories(out.getParent());
        Files.writeString(out, csv);

        var table = new StringBuilder("\n=== fork-failure benchmark [" + label + "], each B step takes "
                + stepMillis + " ms ===\n");
        table.append(String.format("%-4s %-4s %-10s %-8s %-8s %-10s %s%n",
                "N", "rep", "B run", "wasted", "w.undos", "B skipped", "failure->compensated"));
        runs.forEach(run -> table.append(String.format("%-4d %-4d %-10d %-8d %-8d %-10d %d ms%n",
                run.branchLength(), run.repetition(), run.bStepsRun(), run.wastedSteps(), run.wastedUndos(),
                run.bStepsSkipped(), run.failureToCompensatedMillis())));
        table.append("written to ").append(out.toAbsolutePath()).append('\n');
        System.out.println(table);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
