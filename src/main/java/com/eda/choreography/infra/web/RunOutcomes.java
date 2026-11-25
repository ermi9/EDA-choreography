package com.eda.choreography.infra.web;

import com.eda.choreography.domain.compensation.CompensationRequest;
import com.eda.choreography.domain.message.ChoreographyMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The latest outcomes of instances, as seen on the completed and compensated topics, so the
 * editor can show how a run it started ended. Only the newest are kept; this is a window for
 * people watching, not a record.
 */
public class RunOutcomes {

    public enum Status { COMPLETED, COMPENSATED, PARKED }

    public record Outcome(Status status, ChoreographyMessage instance) {}

    private final Map<String, Outcome> latest;
    private final Map<String, Map<String, CompensationRequest>> parked;

    public RunOutcomes(int capacity) {
        this.latest = newestOf(capacity);
        this.parked = newestOf(capacity);
    }

    private static <V> Map<String, V> newestOf(int capacity) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > capacity;
            }
        });
    }

    public void record(Status status, ChoreographyMessage instance) {
        latest.put(instance.correlationId(), new Outcome(status, instance));
    }

    /**
     * An undo that was given up on. The instance stays parked until it is compensated; the topics
     * are read in no fixed order after a restart, so a compensated instance is not parked again.
     */
    public void park(CompensationRequest request) {
        var id = request.instance().correlationId();
        parked.computeIfAbsent(id, key -> new LinkedHashMap<>()).put(request.runId() + '|' + request.entryId(), request);
        latest.compute(id, (key, known) -> known != null && known.status() == Status.COMPENSATED
                ? known
                : new Outcome(Status.PARKED, request.instance()));
    }

    /** Takes the parked undo requests of an instance, to send them again. */
    public List<CompensationRequest> takeParked(String correlationId) {
        var requests = parked.remove(correlationId);
        if (requests == null) {
            return List.of();
        }
        latest.computeIfPresent(correlationId, (key, known) -> known.status() == Status.PARKED ? null : known);
        return new ArrayList<>(requests.values());
    }

    public Optional<Outcome> of(String correlationId) {
        return Optional.ofNullable(latest.get(correlationId));
    }
}
