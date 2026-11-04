package com.eda.choreography.domain.join;

class InMemoryForkSkipsTest implements ForkSkipsContract {

    private final ForkSkips skips = new InMemoryForkSkips();

    @Override
    public ForkSkips skips() {
        return skips;
    }
}
