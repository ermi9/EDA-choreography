package com.eda.choreography.infra.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.domain.join.JoinKey;
import com.eda.choreography.domain.join.JoinState;
import com.eda.choreography.domain.join.JoinStateStore;
import com.eda.choreography.domain.join.JoinStateStoreContract;
import com.eda.choreography.infra.AbstractInfraIT;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The Redis store, against a real Redis, held to the same contract as the in-memory store. */
class RedisJoinStateStoreIT extends AbstractInfraIT implements JoinStateStoreContract {

    @Autowired
    JoinStateStore store;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void forgetEarlierJoins() {
        redis.delete(redis.keys("choreography:join:*"));
    }

    @Override
    public JoinStateStore store() {
        return store;
    }

    @Test
    void keepsStateOnlyForTheConfiguredTimeToLive() {
        var key = new JoinKey("corr-ttl", "J");

        store.save(new JoinState(key, 2, Set.of("B"), false));

        var ttl = redis.getExpire(RedisJoinStateStore.redisKey(key));
        assertThat(Duration.ofSeconds(ttl)).isBetween(Duration.ofDays(7).minusMinutes(1), Duration.ofDays(7));
    }
}
