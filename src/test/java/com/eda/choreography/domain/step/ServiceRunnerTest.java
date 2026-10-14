package com.eda.choreography.domain.step;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.compensation.CompensationTrigger;
import com.eda.choreography.domain.join.InMemoryJoinDeadlines;
import com.eda.choreography.domain.join.InMemoryJoinStateStore;
import com.eda.choreography.domain.join.JoinStateMachine;
import com.eda.choreography.domain.message.ChoreographyMessage;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServiceRunnerTest {

    private final RecordingPublisher publisher = new RecordingPublisher();
    private final CompensationTrigger trigger = new CompensationTrigger(new RecordingCompensations());

    /** "confirm" joins two branches in "checkout", and is a plain step in "returns". */
    private final ServiceRunner confirm = service("confirm");

    @Test
    void aMessageOfAFlowWhereTheStepJoinsWaitsForTheOtherBranch() {
        var forked = ChoreographyMessage.start("order-42", "checkout", Map.of()).recordStep("reserve", Map.of());

        confirm.handle(forked.recordStep("pay", Map.of()).addressedTo("confirm"));
        assertThat(publisher.sent).isEmpty();

        confirm.handle(forked.recordStep("ship", Map.of()).addressedTo("confirm"));
        assertThat(publisher.sent).singleElement().satisfies(sent -> assertThat(sent.stepId()).isEqualTo("notify"));
    }

    @Test
    void aMessageOfAFlowWhereTheStepIsPlainRunsAtOnce() {
        var returned = ChoreographyMessage.start("return-7", "returns", Map.of()).recordStep("receive", Map.of());

        confirm.handle(returned.addressedTo("confirm"));

        assertThat(publisher.sent).singleElement().satisfies(sent -> assertThat(sent.stepId()).isEqualTo("notify"));
    }

    private ServiceRunner service(String name) {
        var step = new StepRunner(name, message -> Map.of("ok", true), (stepId, message) -> List.of("notify"), publisher, trigger);
        var join = new JoinRunner(step, JoinWaits.fixed(2, Duration.ofMinutes(1)),
                new JoinStateMachine(new InMemoryJoinStateStore()), new InMemoryJoinDeadlines(), trigger, Clock.systemUTC());
        return new ServiceRunner(step, join, (flow, stepId) -> flow.equals("checkout") && stepId.equals("confirm"));
    }
}
