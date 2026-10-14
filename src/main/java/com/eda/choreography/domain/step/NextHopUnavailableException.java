package com.eda.choreography.domain.step;

import java.util.List;

/**
 * A step ran, but a service it hands on to takes no work: discovery does not know it yet, or it
 * is not live. The step publishes nothing and throws, so the adapter can wait for the service
 * (a late-bound service may still appear) and run the step again. If the service never comes,
 * the adapter calls {@link StepRunner#failNextHop}.
 */
public class NextHopUnavailableException extends RuntimeException {

    private final List<String> services;

    public NextHopUnavailableException(String step, List<String> services) {
        super("step " + step + " cannot hand on: " + services + " take no work");
        this.services = List.copyOf(services);
    }

    public List<String> services() {
        return services;
    }
}
