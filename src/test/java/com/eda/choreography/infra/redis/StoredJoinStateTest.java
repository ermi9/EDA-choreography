package com.eda.choreography.infra.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StoredJoinStateTest {

    @Test
    void readsAJoinStoredBeforeBranchMessagesWereKept() {
        var json = """
                {"correlationId":"corr-1","joinId":"J","expectedBranches":2,"arrivedBranches":["B"],"fired":false}
                """;

        var state = JsonMapper.builder().build().readValue(json, StoredJoinState.class).toDomain();

        assertThat(state.arrivedBranches()).containsExactly("B");
        assertThat(state.branchMessages()).isEmpty();
    }
}
