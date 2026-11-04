package com.eda.choreography.domain.join;

/**
 * Lets the branches of a fork stop early once one of them has failed: each branch carries only
 * its own copy of the message, so the fact that a sibling failed has to live somewhere they can
 * all read it.
 *
 * <p>Two facts are kept. A fork's failure, keyed like its join, only ever goes from not failed to
 * failed, so any number of services can set and read it without coordinating. And the decision
 * made for each run of a step inside a fork, keyed by the entry id that run records if it
 * completes, which is made once and then repeated. The second is what makes skipping safe under
 * at-least-once delivery: a redelivered message may belong to a step that already did its work
 * before it crashed, and that step must run again so its entry is recorded and undone, never be
 * skipped and forgotten.
 *
 * <p>The flag is an optimisation only. An implementation that cannot reach its store answers
 * "run" and lets {@link #markFailed} go unrecorded; the instance is then as correct as without
 * the flag, just slower to stop.
 */
public interface ForkSkips {

    /** Never skips and remembers nothing: forks run every branch to its end. */
    ForkSkips NONE = new ForkSkips() {
        @Override
        public void markFailed(JoinKey fork) {}

        @Override
        public boolean skips(JoinKey fork, String entryId) {
            return false;
        }
    };

    /** Records that a branch of the fork closed by {@code fork} has failed. Setting it again changes nothing. */
    void markFailed(JoinKey fork);

    /**
     * Whether the step whose completed entry would be {@code entryId} is skipped. The first ask
     * for an entry decides, by whether the fork has failed by then; every later ask for that
     * entry gets the same answer.
     */
    boolean skips(JoinKey fork, String entryId);
}
