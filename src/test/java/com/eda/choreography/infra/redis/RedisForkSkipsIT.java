package com.eda.choreography.infra.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.join.ForkSkips;
import com.eda.choreography.domain.join.ForkSkipsContract;
import com.eda.choreography.domain.join.JoinKey;
import com.eda.choreography.infra.AbstractInfraIT;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The Redis fork skips, against a real Redis, held to the same contract as the in-memory ones. */
class RedisForkSkipsIT extends AbstractInfraIT implements ForkSkipsContract {

    @Autowired
    ForkSkips skips;

    @Autowired
    StringRedisTemplate redis;

    @Value("${choreography.join.state-ttl}")
    Duration stateTimeToLive;

    @BeforeEach
    void forgetEarlierForks() {
        redis.delete(redis.keys("choreography:fork-*"));
    }

    @Override
    public ForkSkips skips() {
        return skips;
    }

    @Test
    void keepsFailuresAndDecisionsAsLongAsJoinState() {
        var fork = new JoinKey("corr-ttl", "J");

        skips.markFailed(fork);
        skips.skips(fork, "B:ttl");

        assertThat(redis.getExpire(RedisForkSkips.failedKey(fork)))
                .isPositive().isLessThanOrEqualTo(stateTimeToLive.toSeconds());
        assertThat(redis.getExpire(RedisForkSkips.decisionKey("B:ttl")))
                .isPositive().isLessThanOrEqualTo(stateTimeToLive.toSeconds());
    }
}
