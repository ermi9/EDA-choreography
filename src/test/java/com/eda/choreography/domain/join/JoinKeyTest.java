package com.eda.choreography.domain.join;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JoinKeyTest {

    @Test
    void twoJoinsOfOneInstanceHaveDifferentKeys() {
        assertThat(new JoinKey("corr-1", "J")).isNotEqualTo(new JoinKey("corr-1", "K"));
        assertThat(new JoinKey("corr-1", "J")).isEqualTo(new JoinKey("corr-1", "J"));
    }

    @Test
    void rejectsBlankParts() {
        assertThatThrownBy(() -> new JoinKey("", "J")).isInstanceOf(JoinProtocolException.class);
        assertThatThrownBy(() -> new JoinKey("corr-1", null)).isInstanceOf(JoinProtocolException.class);
    }
}
