package com.eda.choreography.domain.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.plan.PlanNode.Split;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class PlannedRoutesTest {

    /** Test stand-in for the condition language: {@code field=value} holds when the result's field has that value. */
    private static final Conditions FIELD_EQUALS = (condition, result) -> {
        var parts = condition.split("=", 2);
        return Objects.equals(String.valueOf(result.get(parts[0])), parts[1]);
    };

    private static final PlanModel LOAN = PlanModel.builder("loan")
            .task("intake")
            .task("assess", Split.EXCLUSIVE)
            .task("review")
            .task("fast-track")
            .task("approve")
            .edge("intake", "assess")
            .edge("assess", "review", "risk=HIGH")
            .edge("assess", "fast-track", "risk=LOW")
            .defaultEdge("assess", "approve")
            .edge("review", "approve")
            .edge("fast-track", "approve")
            .build();

    private static final PlanModel STRICT = PlanModel.builder("strict")
            .task("assess", Split.EXCLUSIVE)
            .task("review")
            .task("approve")
            .edge("assess", "review", "risk=HIGH")
            .edge("assess", "approve", "risk=LOW")
            .build();

    private final PlannedRoutes routes = new PlannedRoutes(
            Plans.of(LOAN, STRICT, PlanModelTest.checkout()), FIELD_EQUALS);

    @Test
    void aStepWithOneEdgeOutHandsOnAlongIt() {
        var message = start("loan").recordStep("review", Map.of("notes", "ok"));

        assertThat(routes.after("review", message)).containsExactly("approve");
    }

    @Test
    void theLastStepHasNowhereToGo() {
        var message = start("loan").recordStep("approve", Map.of("approved", true));

        assertThat(routes.after("approve", message)).isEmpty();
    }

    @Test
    void aForkHandsOnToEveryBranch() {
        var message = start("checkout").recordStep("reserve", Map.of("quantity", 10));

        assertThat(routes.after("reserve", message)).containsExactly("pay", "ship");
    }

    @Test
    void aChoiceGoesWhereTheStepsOwnResultSays() {
        var high = start("loan").recordStep("assess", Map.of("risk", "HIGH"));
        var low = start("loan").recordStep("assess", Map.of("risk", "LOW"));
        var other = start("loan").recordStep("assess", Map.of("risk", "MEDIUM"));

        assertThat(routes.after("assess", high)).containsExactly("review");
        assertThat(routes.after("assess", low)).containsExactly("fast-track");
        assertThat(routes.after("assess", other)).containsExactly("approve");
    }

    @Test
    void aChoiceTakesTheFirstEdgeWhoseConditionHoldsInTheOrderTheyWereDrawn() {
        var overlapping = PlanModel.builder("overlap")
                .task("assess", Split.EXCLUSIVE)
                .task("first")
                .task("second")
                .edge("assess", "first", "risk=HIGH")
                .edge("assess", "second", "risk=HIGH")
                .build();
        var routes = new PlannedRoutes(Plans.of(overlapping), FIELD_EQUALS);

        assertThat(routes.after("assess", start("overlap").recordStep("assess", Map.of("risk", "HIGH"))))
                .containsExactly("first");
    }

    @Test
    void aChoiceLooksOnlyAtTheResultOfTheStepItLeaves() {
        // An earlier step's "risk" must not steer the choice.
        var message = start("loan")
                .recordStep("intake", Map.of("risk", "HIGH"))
                .recordStep("assess", Map.of("risk", "LOW"));

        assertThat(routes.after("assess", message)).containsExactly("fast-track");
    }

    @Test
    void aChoiceWithoutADefaultWhereNoConditionHoldsHasNoRoute() {
        var message = start("strict").recordStep("assess", Map.of("risk", "MEDIUM"));

        assertThatThrownBy(() -> routes.after("assess", message))
                .isInstanceOf(NoRouteException.class)
                .hasMessageContaining("assess");
    }

    @Test
    void aFlowOrStepWithoutAPlanHasNoRoute() {
        assertThatThrownBy(() -> routes.after("assess", start("unknown").recordStep("assess", Map.of())))
                .isInstanceOf(NoRouteException.class)
                .hasMessageContaining("unknown");
        assertThatThrownBy(() -> routes.after("ship", start("loan").recordStep("ship", Map.of())))
                .isInstanceOf(NoRouteException.class)
                .hasMessageContaining("ship");
    }

    @Test
    void knowsTheJoinOfAStepInTheFlowOfTheMessage() {
        var message = start("checkout").recordStep("reserve", Map.of()).recordStep("pay", Map.of());

        assertThat(routes.enclosingJoin("pay", message)).contains("confirm");
        assertThat(routes.enclosingJoin("review", start("loan"))).isEmpty();
    }

    @Test
    void namesTheServiceOfEachStepOfTheMessagesFlow() {
        var twice = PlanModel.builder("notify-twice")
                .task("notify-buyer", "notify", Split.NONE)
                .task("notify-seller", "notify", Split.NONE)
                .edge("notify-buyer", "notify-seller")
                .build();
        var routes = new PlannedRoutes(Plans.of(twice), FIELD_EQUALS);

        assertThat(routes.serviceOf("notify-seller", start("notify-twice"))).isEqualTo("notify");
    }

    @Test
    void aStepRunsAsOftenAsItsFlowAllowsOrElseAsTheDefault() {
        var capped = PlanModel.builder("capped").task("poll").maxRuns(3).build();
        var routes = new PlannedRoutes(Plans.of(capped, LOAN), FIELD_EQUALS, 100);

        assertThat(routes.maxRuns("poll", start("capped"))).isEqualTo(3);
        assertThat(routes.maxRuns("review", start("loan"))).isEqualTo(100);
    }

    private static ChoreographyMessage start(String flow) {
        return ChoreographyMessage.start("order-42", flow, Map.of());
    }
}
