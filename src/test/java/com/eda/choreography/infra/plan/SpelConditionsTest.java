package com.eda.choreography.infra.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpelConditionsTest {

    private final SpelConditions conditions = new SpelConditions();

    private final Map<String, Object> result = Map.of(
            "risk", "HIGH",
            "amount", 1500,
            "approved", true,
            "customer", Map.of("tier", "gold"));

    @Test
    void testsAFieldOfTheStepsResult() {
        assertThat(conditions.holds("#result['risk'] == 'HIGH'", result)).isTrue();
        assertThat(conditions.holds("#result['risk'] == 'LOW'", result)).isFalse();
    }

    @Test
    void comparesNumbersCombinesTestsAndReadsNestedObjects() {
        assertThat(conditions.holds("#result['amount'] > 1000", result)).isTrue();
        assertThat(conditions.holds("#result['amount'] > 1000 and #result['risk'] != 'HIGH'", result)).isFalse();
        assertThat(conditions.holds("#result['customer']['tier'] == 'gold'", result)).isTrue();
        assertThat(conditions.holds("#result['approved']", result)).isTrue();
    }

    @Test
    void aFieldTheResultDoesNotHaveIsNull() {
        assertThat(conditions.holds("#result['reason'] == null", result)).isTrue();
    }

    @Test
    void aConditionThatIsNotTrueOrFalseIsAnError() {
        assertThatThrownBy(() -> conditions.holds("#result['risk']", result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#result['risk']");
        assertThatThrownBy(() -> conditions.holds("#result['reason']", result))
                .isInstanceOf(IllegalArgumentException.class);
        // Not even a string that reads like one.
        assertThatThrownBy(() -> conditions.holds("#result['flag']", Map.of("flag", "true")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aConditionThatDoesNotParseIsAnError() {
        assertThatThrownBy(() -> conditions.holds("#result['risk' ==", result)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aConditionCanOnlyReadTheResultNotReachTypesCallMethodsOrChangeIt() {
        // Conditions come from a drawn flow, so they must not run code in the service.
        assertThatThrownBy(() -> conditions.holds("T(java.lang.System).exit(1) == null", result))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> conditions.holds("#result.getClass() != null", result))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> conditions.holds("new java.io.File('x').exists()", result))
                .isInstanceOf(RuntimeException.class);
        var mutable = new HashMap<String, Object>(result);
        assertThatThrownBy(() -> conditions.holds("(#result['risk'] = 'LOW') == 'LOW'", mutable))
                .isInstanceOf(RuntimeException.class);
        assertThat(mutable).containsEntry("risk", "HIGH");
    }
}
