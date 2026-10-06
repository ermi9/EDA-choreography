package com.eda.choreography.domain.join;

class InMemoryJoinDeadlinesTest implements JoinDeadlinesContract {

    private final JoinDeadlines deadlines = new InMemoryJoinDeadlines();

    @Override
    public JoinDeadlines deadlines() {
        return deadlines;
    }
}
