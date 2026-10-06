package com.eda.choreography.domain.join;

/**
 * Tells the service running a join that the join's wait is over for one instance. The notice
 * travels the way that instance's branches do, so the service takes it in turn with them and
 * never times a join out while one of its branches is being handled.
 */
public interface JoinTimeoutNotices {

    /** Returns only once the notice is accepted. */
    void publish(JoinKey key);
}
