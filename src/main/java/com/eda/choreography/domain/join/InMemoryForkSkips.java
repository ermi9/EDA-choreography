package com.eda.choreography.domain.join;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** {@link ForkSkips} held in process memory, for tests and single-process runs. */
public final class InMemoryForkSkips implements ForkSkips {

    private final Set<JoinKey> failed = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> decisions = new ConcurrentHashMap<>();

    @Override
    public void markFailed(JoinKey fork) {
        failed.add(fork);
    }

    @Override
    public boolean skips(JoinKey fork, String entryId) {
        return decisions.computeIfAbsent(entryId, id -> failed.contains(fork));
    }
}
