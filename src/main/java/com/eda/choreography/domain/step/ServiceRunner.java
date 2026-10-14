package com.eda.choreography.domain.step;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * Everything one service does with the messages on its topic. A service can run a plain step in
 * one flow and the join step of another, so each message goes to the join or straight to the
 * step, as the plan of its flow says.
 */
public final class ServiceRunner {

    private final StepRunner step;
    private final JoinRunner join;
    private final BiPredicate<String, String> isJoin;

    /**
     * @param join   the join this service runs where its step joins branches; built on {@code step}
     * @param isJoin whether a step of a flow, given as (flow name, step id), is a join
     */
    public ServiceRunner(StepRunner step, JoinRunner join, BiPredicate<String, String> isJoin) {
        this.step = Objects.requireNonNull(step, "step");
        this.join = Objects.requireNonNull(join, "join");
        this.isJoin = Objects.requireNonNull(isJoin, "isJoin");
        if (!join.stepId().equals(step.stepId())) {
            throw new IllegalArgumentException("join " + join.stepId() + " is not built on step " + step.stepId());
        }
    }

    /** The service, whose topic this runner consumes. */
    public String stepId() {
        return step.stepId();
    }

    public void handle(ChoreographyMessage message) {
        if (joins(message)) {
            join.handle(message);
        } else {
            step.handle(message);
        }
    }

    public void fail(ChoreographyMessage message) {
        if (joins(message)) {
            join.fail(message);
        } else {
            step.fail(message);
        }
    }

    public void failNextHop(ChoreographyMessage message) {
        if (joins(message)) {
            join.failNextHop(message);
        } else {
            step.failNextHop(message);
        }
    }

    /** A timeout notice only ever concerns this service's join. */
    public void timeOut(String correlationId) {
        join.timeOut(correlationId);
    }

    private boolean joins(ChoreographyMessage message) {
        var addressed = message.step() != null ? message.step() : step.stepId();
        return isJoin.test(message.flowName(), addressed);
    }
}
