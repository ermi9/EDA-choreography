package com.eda.choreography.domain.join;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BranchArrivalTest {

    private static final JoinKey JOIN = new JoinKey("corr-1", "J");

    @Test
    void rejectsAJoinWaitingForNoBranches() {
        assertThatThrownBy(() -> new BranchArrival(JOIN, "B", 0)).isInstanceOf(JoinProtocolException.class);
    }

    @Test
    void rejectsAMissingKeyOrBranch() {
        assertThatThrownBy(() -> new BranchArrival(JOIN, " ", 2)).isInstanceOf(JoinProtocolException.class);
        assertThatThrownBy(() -> new BranchArrival(null, "B", 2)).isInstanceOf(JoinProtocolException.class);
    }
}
