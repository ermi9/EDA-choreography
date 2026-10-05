package com.eda.choreography.infra.redis;

import com.eda.choreography.domain.join.JoinKey;
import com.eda.choreography.domain.join.JoinState;
import com.eda.choreography.domain.join.JoinStateStore;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps join state in Redis, one JSON string per join, so a join survives a restart of the
 * consumer that is waiting on it.
 *
 * <p>Every save resets the key's time to live. The state of a fired join has to outlive any
 * redelivery of a branch that already arrived, or that branch would open a fresh join that can
 * never complete. A redelivery cannot happen once the record carrying it has left the topic, so
 * the TTL is set to the topics' retention.
 */
public class RedisJoinStateStore implements JoinStateStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StringRedisTemplate redis;
    private final Duration timeToLive;

    public RedisJoinStateStore(StringRedisTemplate redis, Duration timeToLive) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.timeToLive = Objects.requireNonNull(timeToLive, "timeToLive");
    }

    @Override
    public Optional<JoinState> find(JoinKey key) {
        return Optional.ofNullable(redis.opsForValue().get(redisKey(key)))
                .map(json -> JSON.readValue(json, StoredJoinState.class).toDomain());
    }

    @Override
    public void save(JoinState state) {
        redis.opsForValue().set(redisKey(state.key()), JSON.writeValueAsString(StoredJoinState.from(state)), timeToLive);
    }

    /**
     * The correlation id is length-prefixed, so ids that contain the separator cannot make two
     * different joins share a key.
     */
    static String redisKey(JoinKey key) {
        return "choreography:join:" + key.correlationId().length() + ':' + key.correlationId() + ':' + key.joinId();
    }
}
