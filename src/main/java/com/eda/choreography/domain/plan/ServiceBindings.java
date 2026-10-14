package com.eda.choreography.domain.plan;

import com.eda.choreography.domain.resolution.ResolutionSource;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which of a plan's services discovery knows when the plan is made. A known service is bound;
 * an unknown one is late-bound: the plan is accepted, and the service is resolved when a step
 * first hands on to it. If it has not appeared by then (within the late-binding wait), that step
 * fails and the instance is undone.
 *
 * <p>A service counts as known even when it is not live at the moment: liveness changes, and is
 * checked on every hand-on.
 *
 * @param bound     the services discovery knows
 * @param lateBound the services it does not know yet
 */
public record ServiceBindings(Set<String> bound, Set<String> lateBound) {

    public ServiceBindings {
        bound = Set.copyOf(bound);
        lateBound = Set.copyOf(lateBound);
    }

    public static ServiceBindings of(PlanModel plan, ResolutionSource catalog) {
        var bound = new LinkedHashSet<String>();
        var lateBound = new LinkedHashSet<String>();
        for (var service : plan.services()) {
            (catalog.resolve(service).isPresent() ? bound : lateBound).add(service);
        }
        return new ServiceBindings(bound, lateBound);
    }

    public boolean fullyBound() {
        return lateBound.isEmpty();
    }
}
