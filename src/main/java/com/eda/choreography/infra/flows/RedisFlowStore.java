package com.eda.choreography.infra.flows;

import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Flows in one Redis hash, name to BPMN. Every engine process reads the same flows: the one that
 * takes uploads and the ones that run steps.
 */
public class RedisFlowStore implements FlowStore {

    static final String KEY = "choreography:flows";

    private final StringRedisTemplate redis;

    public RedisFlowStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public void save(String name, String bpmn) {
        redis.opsForHash().put(KEY, name, bpmn);
    }

    @Override
    public Optional<String> find(String name) {
        return Optional.ofNullable((String) redis.opsForHash().get(KEY, name));
    }

    @Override
    public SortedSet<String> names() {
        var names = new TreeSet<String>();
        redis.opsForHash().keys(KEY).forEach(name -> names.add((String) name));
        return names;
    }
}
