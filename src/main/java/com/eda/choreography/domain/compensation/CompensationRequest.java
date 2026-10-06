package com.eda.choreography.domain.compensation;

import com.eda.choreography.domain.message.ChoreographyMessage;
import com.eda.choreography.domain.message.MalformedMessageException;
import com.eda.choreography.domain.trace.TraceEntry;
import java.util.Set;

/**
 * Asks one service to undo one of its trace entries.
 *
 * <p>The request carries the whole instance, so the receiver can work out the compensation
 * order from the trace on its own: which stage its entry is in, and how many entries of the
 * stage before have to be undone first. It also names who sent it, which is how the receiver
 * counts those entries off.
 *
 * @param runId         what started this compensation (the failed entry, or the join that timed
 *                      out); two runs over one instance are counted apart
 * @param entryId       the trace entry to undo
 * @param previousId    the entry just undone in the stage before, or {@code runId} for the
 *                      first stage
 * @param instance      the instance as it stood when compensation started
 * @param alreadyUndone the entries of the instance an earlier compensation already undid, so
 *                      this one leaves them out; empty, or absent on the wire, for the first
 */
public record CompensationRequest(
        String runId, String entryId, String previousId, ChoreographyMessage instance, Set<String> alreadyUndone) {

    public CompensationRequest {
        requireText(runId, "runId");
        requireText(entryId, "entryId");
        requireText(previousId, "previousId");
        if (instance == null) {
            throw new MalformedMessageException("instance must be non-null");
        }
        if (instance.trace().stream().noneMatch(entry -> entry.id().equals(entryId) && entry.needsCompensation())) {
            throw new MalformedMessageException(
                    "entry " + entryId + " is not a completed step of " + instance.correlationId());
        }
        alreadyUndone = alreadyUndone == null ? Set.of() : Set.copyOf(alreadyUndone);
        if (alreadyUndone.contains(entryId)) {
            throw new MalformedMessageException("entry " + entryId + " was already undone");
        }
    }

    /** A request of a compensation that is the first over its instance. */
    public CompensationRequest(String runId, String entryId, String previousId, ChoreographyMessage instance) {
        this(runId, entryId, previousId, instance, Set.of());
    }

    /** The entry to undo. */
    public TraceEntry entry() {
        return instance.trace().stream().filter(entry -> entry.id().equals(entryId)).findFirst().orElseThrow();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new MalformedMessageException(name + " must be non-blank");
        }
    }
}
