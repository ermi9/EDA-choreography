package com.eda.choreography.infra.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.eda.choreography.domain.join.JoinKey;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The flag only saves work, so a Redis that cannot be reached must never fail a step. */
class RedisForkSkipsTest {

    private static final JoinKey FORK = new JoinKey("corr-1", "J");

    // Nothing listens on port 1, so every command fails at once.
    private final LettuceConnectionFactory unreachable = new LettuceConnectionFactory("localhost", 1);
    private final RedisForkSkips skips;

    RedisForkSkipsTest() {
        unreachable.afterPropertiesSet();
        unreachable.start();
        skips = new RedisForkSkips(new StringRedisTemplate(unreachable), Duration.ofDays(7));
    }

    @AfterEach
    void close() {
        unreachable.destroy();
    }

    @Test
    void aStepRunsWhenItsForkCannotBeAskedAbout() {
        assertThat(skips.skips(FORK, "B:1")).isFalse();
    }

    @Test
    void aFailureThatCannotBeRecordedDoesNotStopTheFailedStepReportingIt() {
        assertThatCode(() -> skips.markFailed(FORK)).doesNotThrowAnyException();
    }
}
