package com.eda.choreography.infra.flows;

import static org.assertj.core.api.Assertions.assertThat;

import com.eda.choreography.infra.AbstractInfraIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The flow store against a real Redis. */
class RedisFlowStoreIT extends AbstractInfraIT {

    @Autowired
    FlowStore store;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void forgetEarlierFlows() {
        redis.delete(RedisFlowStore.KEY);
    }

    @Test
    void keepsFlowsByNameAndReplacesOneSavedAgain() {
        store.save("loan", "<first/>");
        store.save("checkout", "<other/>");
        store.save("loan", "<second/>");

        assertThat(store.find("loan")).contains("<second/>");
        assertThat(store.find("returns")).isEmpty();
        assertThat(store.names()).containsExactly("checkout", "loan");
    }
}
