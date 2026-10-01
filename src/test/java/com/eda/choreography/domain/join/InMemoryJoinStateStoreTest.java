package com.eda.choreography.domain.join;

class InMemoryJoinStateStoreTest extends JoinStateStoreContract {

    private final JoinStateStore store = new InMemoryJoinStateStore();

    @Override
    protected JoinStateStore store() {
        return store;
    }
}
