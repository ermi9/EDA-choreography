package com.eda.choreography.domain.join;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** {@link JoinDeadlines} held in process memory, for tests and single-process runs. */
public final class InMemoryJoinDeadlines implements JoinDeadlines {

    private final Map<JoinKey, Instant> deadlines = new ConcurrentHashMap<>();

    @Override
    public void setIfAbsent(JoinKey key, Instant deadline) {
        deadlines.putIfAbsent(key, deadline);
    }

    @Override
    public List<JoinKey> dueBy(Instant now, int limit) {
        return deadlines.entrySet().stream()
                .filter(entry -> !entry.getValue().isAfter(now))
                .sorted(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public void remove(JoinKey key) {
        deadlines.remove(key);
    }
}
