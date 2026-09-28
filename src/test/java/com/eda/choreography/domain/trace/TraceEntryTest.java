package com.eda.choreography.domain.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class TraceEntryTest {

    private static final String CORRELATION = "corr-1";

    @Test
    void entryRejectsItselfAsParentAndBlankIdentity() {
        assertThatThrownBy(() -> done("A", "A")).isInstanceOf(MalformedTraceException.class);
        assertThatThrownBy(() -> done(" ")).isInstanceOf(MalformedTraceException.class);
        assertThatThrownBy(() -> TraceEntry.completed("A", "", "svc", "r"))
                .isInstanceOf(MalformedTraceException.class);
    }

    @Test
    void onlyCompletedEntriesNeedCompensation() {
        assertThat(done("A").needsCompensation()).isTrue();
        assertThat(TraceEntry.failed("B", CORRELATION, "svc-B").needsCompensation()).isFalse();
        assertThat(done("C", "A", "B").parents()).isEqualTo(Set.of("A", "B"));
    }

    private static TraceEntry done(String id, String... parents) {
        return TraceEntry.completed(id, CORRELATION, "svc-" + id, "result-" + id, parents);
    }
}
