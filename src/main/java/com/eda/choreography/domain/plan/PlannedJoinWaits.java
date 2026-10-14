package com.eda.choreography.domain.plan;

import com.eda.choreography.domain.step.JoinWait;
import com.eda.choreography.domain.step.JoinWaits;
import java.time.Duration;
import java.util.Objects;

/**
 * How a join waits, read from the plan of the branch's flow: one branch per edge leaving the
 * fork, for the flow's join timeout, or for a default when the flow sets none.
 */
public final class PlannedJoinWaits implements JoinWaits {

    private final Plans plans;
    private final Duration defaultTimeout;

    public PlannedJoinWaits(Plans plans, Duration defaultTimeout) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout");
    }

    @Override
    public JoinWait forJoin(String flowName, String joinStep) {
        var plan = plans.forFlow(flowName).orElseThrow(() ->
                new NoRouteException("there is no plan for flow " + flowName));
        return new JoinWait(plan.expectedBranches(joinStep), plan.joinTimeout().orElse(defaultTimeout));
    }
}
