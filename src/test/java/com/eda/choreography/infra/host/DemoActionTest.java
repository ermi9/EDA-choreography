package com.eda.choreography.infra.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DemoActionTest {

    @Test
    void answersWithTheInputAndWhoRanWhichStep() {
        var message = ChoreographyMessage.start("order-42", "order", Map.of("amount", 5000)).addressedTo("Task_buyer");

        assertThat(new DemoAction("notify").execute(message))
                .isEqualTo(Map.of("amount", 5000, "service", "notify", "step", "Task_buyer", "visits", 1));
    }

    @Test
    void countsItsVisitsSoALoopCanEndOnThem() {
        var secondRound = ChoreographyMessage.start("order-42", "retry", Map.of())
                .recordStep("draft", Map.of()).recordStep("review", Map.of()).recordStep("draft", Map.of());

        assertThat(new DemoAction("review").execute(secondRound).get("visits")).isEqualTo(2);
    }

    @Test
    void failsOnlyOnTheVisitTheInputNames() {
        var input = Map.<String, Object>of("failAt", "review", "failAtVisit", 2);
        var firstRound = ChoreographyMessage.start("order-42", "retry", input).recordStep("draft", Map.of());
        var secondRound = firstRound.recordStep("review", Map.of()).recordStep("draft", Map.of());

        assertThat(new DemoAction("review").execute(firstRound)).doesNotContainKeys("failAt", "failAtVisit");
        assertThatThrownBy(() -> new DemoAction("review").execute(secondRound)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsWhenTheInputAsksThisStepOrServiceTo() {
        var atService = ChoreographyMessage.start("order-42", "order", Map.of("failAt", "ship"));
        var atStep = ChoreographyMessage.start("order-42", "order", Map.of("failAt", "Task_buyer")).addressedTo("Task_buyer");

        assertThatThrownBy(() -> new DemoAction("ship").execute(atService)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DemoAction("notify").execute(atStep)).isInstanceOf(IllegalStateException.class);
        assertThat(new DemoAction("pay").execute(atService)).doesNotContainKey("failAt");
    }
}
