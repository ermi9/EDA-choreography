package com.eda.choreography.domain.join;

class InMemoryJoinStateStoreTest implements JoinStateStoreContract {

    private final JoinStateStore store = new InMemoryJoinStateStore();

    @Override
    public JoinStateStore store() {
        return store;
    }
}
