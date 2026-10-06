package com.eda.choreography.domain.join;

import java.time.Clock;
import java.util.Objects;

/**
 * Times out the joins whose deadline has passed, by sending each a notice and then forgetting
 * its deadline. It never touches join state itself; the join's own service does that when the
 * notice arrives.
 *
 * <p>A sweep that stops between sending a notice and forgetting the deadline sends the notice
 * again next time, and two sweepers running at once may both send one. Either way the join is
 * timed out twice, which is harmless, so sweepers need no lock.
 */
public final class JoinDeadlineSweeper {

    private final JoinDeadlines deadlines;
    private final JoinTimeoutNotices notices;
    private final Clock clock;
    private final int batchSize;

    public JoinDeadlineSweeper(JoinDeadlines deadlines, JoinTimeoutNotices notices, Clock clock, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, was " + batchSize);
        }
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
        this.notices = Objects.requireNonNull(notices, "notices");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.batchSize = batchSize;
    }

    /** Sends a notice for every join due now, a batch at a time, and returns how many it sent. */
    public int sweep() {
        var now = clock.instant();
        int sent = 0;
        for (var due = deadlines.dueBy(now, batchSize); !due.isEmpty(); due = deadlines.dueBy(now, batchSize)) {
            for (var key : due) {
                notices.publish(key);
                deadlines.remove(key);
                sent++;
            }
        }
        return sent;
    }
}
