package com.eda.choreography.domain.plan;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Finds the plan of a flow by its name, the name every message of that flow carries. */
@FunctionalInterface
public interface Plans {

    Optional<PlanModel> forFlow(String flowName);

    /** A fixed set of plans. */
    static Plans of(PlanModel... plans) {
        Map<String, PlanModel> byName = Arrays.stream(plans)
                .collect(Collectors.toUnmodifiableMap(PlanModel::flowName, Function.identity()));
        return flowName -> Optional.ofNullable(byName.get(flowName));
    }
}
