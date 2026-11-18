package com.eda.choreography.infra.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DemoSpecTest {

    @Test
    void namesTheTopicsTheServiceIsReadFrom() {
        var choreography = (Map<?, ?>) DemoSpec.of("pay").get("x-choreography");

        assertThat(((Map<?, ?>) choreography.get("step")).get("topic")).isEqualTo("pay.in");
        assertThat(((Map<?, ?>) choreography.get("undo")).get("topic")).isEqualTo("pay.compensate");
    }

    @Test
    void listsExactlyTheFieldsTheDemoStepAddsToItsResult() {
        var input = Map.<String, Object>of("amount", 5000);
        var result = new DemoAction("pay").execute(ChoreographyMessage.start("c-1", "checkout", input));

        assertThat(result.keySet()).containsExactlyInAnyOrderElementsOf(
                union(input.keySet(), properties("StepResult").keySet()));
    }

    @Test
    void listsTheInputFieldsThatSteerTheDemoStep() {
        assertThat(properties("StepInput")).containsOnlyKeys("failAt", "failAtVisit", "delayMs");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> properties(String schema) {
        var components = (Map<?, ?>) DemoSpec.of("pay").get("components");
        var schemas = (Map<?, ?>) components.get("schemas");
        return (Map<String, ?>) ((Map<?, ?>) schemas.get(schema)).get("properties");
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        var all = new HashSet<String>(a);
        all.addAll(b);
        return all;
    }
}
