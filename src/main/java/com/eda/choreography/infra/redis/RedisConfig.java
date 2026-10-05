package com.eda.choreography.infra.redis;

import com.eda.choreography.domain.join.JoinStateMachine;
import com.eda.choreography.domain.join.JoinStateStore;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis-backed join state. The connection and {@link StringRedisTemplate} come from Spring
 * Boot's {@code spring-boot-data-redis} auto-configuration, which connects lazily.
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    @Bean
    JoinStateStore joinStateStore(
            StringRedisTemplate redis,
            @Value("${choreography.join.state-ttl}") Duration stateTimeToLive) {
        return new RedisJoinStateStore(redis, stateTimeToLive);
    }

    @Bean
    JoinStateMachine joinStateMachine(JoinStateStore store) {
        return new JoinStateMachine(store);
    }
}
