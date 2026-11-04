package com.eda.choreography.infra.redis;

import com.eda.choreography.domain.join.ForkSkips;
import com.eda.choreography.domain.join.JoinKey;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Keeps fork failures and the decisions of steps inside forks in Redis, one plain key each.
 *
 * <p>A failure is a key set to {@code 1}; setting it again is harmless. A decision is set with
 * {@code SET NX}, so when two copies of a message ask at once only the first answer is kept, and
 * the other reads it back. Both keep the join state's time to live: they matter for as long as a
 * branch can still be redelivered.
 *
 * <p>Redis being unreachable never fails a step. The flag only saves work, so a decision that
 * cannot be read or written is "run", and a failure that cannot be recorded is logged and left
 * out. One gap remains: if a decision to run could not be written and the message is redelivered
 * once Redis is back after the fork failed, that copy is skipped although the first may have done
 * its work.
 */
public class RedisForkSkips implements ForkSkips {

    private static final Logger LOG = LoggerFactory.getLogger(RedisForkSkips.class);
    private static final String RUN = "run";
    private static final String SKIP = "skip";

    private final StringRedisTemplate redis;
    private final Duration timeToLive;

    public RedisForkSkips(StringRedisTemplate redis, Duration timeToLive) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.timeToLive = Objects.requireNonNull(timeToLive, "timeToLive");
    }

    @Override
    public void markFailed(JoinKey fork) {
        try {
            redis.opsForValue().set(failedKey(fork), "1", timeToLive);
        } catch (DataAccessException e) {
            LOG.warn("could not mark fork {} failed; its other branches run to their end", fork, e);
        }
    }

    @Override
    public boolean skips(JoinKey fork, String entryId) {
        try {
            var key = decisionKey(entryId);
            var wanted = Boolean.TRUE.equals(redis.hasKey(failedKey(fork))) ? SKIP : RUN;
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, wanted, timeToLive))) {
                return wanted.equals(SKIP);
            }
            return SKIP.equals(redis.opsForValue().get(key));
        } catch (DataAccessException e) {
            LOG.warn("could not ask whether {} of fork {} is skipped; running it", entryId, fork, e);
            return false;
        }
    }

    /** Length-prefixed like {@link RedisJoinStateStore#redisKey}, so two forks cannot share a key. */
    static String failedKey(JoinKey fork) {
        return "choreography:fork-failed:" + fork.correlationId().length() + ':' + fork.correlationId() + ':' + fork.joinId();
    }

    /** An entry id names one run of one step of one instance, so it is a key on its own. */
    static String decisionKey(String entryId) {
        return "choreography:fork-step:" + entryId;
    }
}
