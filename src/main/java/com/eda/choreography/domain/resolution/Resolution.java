package com.eda.choreography.domain.resolution;

/**
 * Where a service takes its work and its undo requests, and whether it may be sent new work.
 *
 * @param inputTopic        where the service consumes the steps it runs
 * @param compensationTopic where the service consumes requests to undo a step
 * @param live              whether discovery last reported the service healthy
 */
public record Resolution(String inputTopic, String compensationTopic, boolean live) {

    public Resolution {
        if (inputTopic == null || inputTopic.isBlank()) {
            throw new IllegalArgumentException("inputTopic must be non-blank");
        }
        if (compensationTopic == null || compensationTopic.isBlank()) {
            throw new IllegalArgumentException("compensationTopic must be non-blank");
        }
    }
}
