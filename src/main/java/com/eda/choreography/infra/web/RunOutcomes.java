package com.eda.choreography.infra.web;

import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The latest outcomes of instances, as seen on the completed and compensated topics, so the
 * editor can show how a run it started ended. Only the newest are kept; this is a window for
 * people watching, not a record.
 */
public class RunOutcomes {

    public enum Status { COMPLETED, COMPENSATED }

    public record Outcome(Status status, ChoreographyMessage instance) {}

    private final Map<String, Outcome> latest;

    public RunOutcomes(int capacity) {
        this.latest = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Outcome> eldest) {
                return size() > capacity;
            }
        });
    }

    public void record(Status status, ChoreographyMessage instance) {
        latest.put(instance.correlationId(), new Outcome(status, instance));
    }

    public Optional<Outcome> of(String correlationId) {
        return Optional.ofNullable(latest.get(correlationId));
    }
}
