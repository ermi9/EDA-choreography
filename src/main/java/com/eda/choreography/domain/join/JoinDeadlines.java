package com.eda.choreography.domain.join;

import java.time.Instant;
import java.util.List;

/**
 * When each open join gives up waiting. A join's deadline is set when its first branch arrives
 * and cleared when it fires; a sweeper times out the joins whose deadline has passed.
 *
 * <p>Deadlines live apart from join state because they are looked up by time, across every
 * instance, while join state is only ever looked up by its key.
 */
public interface JoinDeadlines {

    /** Sets the join's deadline, unless it already has one: later branches do not extend the wait. */
    void setIfAbsent(JoinKey key, Instant deadline);

    /** Up to {@code limit} joins whose deadline is at or before {@code now}, earliest first. */
    List<JoinKey> dueBy(Instant now, int limit);

    /** Forgets the join's deadline; nothing happens if it has none. */
    void remove(JoinKey key);
}
