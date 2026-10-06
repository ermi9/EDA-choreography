package com.eda.choreography.infra.redis;

import com.eda.choreography.domain.join.JoinDeadlines;
import com.eda.choreography.domain.join.JoinKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Keeps join deadlines in one Redis sorted set, scored by deadline in epoch milliseconds, so
 * the due joins are a single range query however many instances are waiting.
 *
 * <p>Setting a deadline is {@code ZADD NX}, so a later branch cannot extend it.
 */
public class RedisJoinDeadlines implements JoinDeadlines {

    static final String KEY = "choreography:join-deadlines";

    private final StringRedisTemplate redis;

    public RedisJoinDeadlines(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public void setIfAbsent(JoinKey key, Instant deadline) {
        redis.opsForZSet().addIfAbsent(KEY, member(key), deadline.toEpochMilli());
    }

    @Override
    public List<JoinKey> dueBy(Instant now, int limit) {
        var due = redis.opsForZSet().rangeByScore(KEY, Double.NEGATIVE_INFINITY, now.toEpochMilli(), 0, limit);
        return due == null ? List.of() : due.stream().map(RedisJoinDeadlines::key).toList();
    }

    @Override
    public void remove(JoinKey key) {
        redis.opsForZSet().remove(KEY, member(key));
    }

    /** Length-prefixed like {@link RedisJoinStateStore#redisKey}, so the key can be read back unambiguously. */
    static String member(JoinKey key) {
        return key.correlationId().length() + ":" + key.correlationId() + ':' + key.joinId();
    }

    static JoinKey key(String member) {
        int colon = member.indexOf(':');
        int start = colon + 1;
        int end = start + Integer.parseInt(member.substring(0, colon));
        return new JoinKey(member.substring(start, end), member.substring(end + 1));
    }
}
