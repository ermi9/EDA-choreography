package com.eda.choreography.infra.redis;

import com.eda.choreography.domain.join.JoinDeadlines;
import com.eda.choreography.domain.join.JoinDeadlinesContract;
import com.eda.choreography.infra.AbstractInfraIT;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The Redis deadlines, against a real Redis, held to the same contract as the in-memory ones. */
class RedisJoinDeadlinesIT extends AbstractInfraIT implements JoinDeadlinesContract {

    @Autowired
    JoinDeadlines deadlines;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void forgetEarlierDeadlines() {
        redis.delete(RedisJoinDeadlines.KEY);
    }

    @Override
    public JoinDeadlines deadlines() {
        return deadlines;
    }
}
