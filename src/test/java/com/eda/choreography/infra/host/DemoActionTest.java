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
                .isEqualTo(Map.of("amount", 5000, "service", "notify", "step", "Task_buyer"));
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
