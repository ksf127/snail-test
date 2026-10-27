package com.company.service.shortlink.infra;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 点击存储：HINCRBY 累加，天然支持多实例汇总。
 */
public class RedisClickStore implements ClickStore {

    private final StringRedisTemplate redisTemplate;

    private final String hashKey;

    public RedisClickStore(StringRedisTemplate redisTemplate, String hashKey) {
        this.redisTemplate = redisTemplate;
        this.hashKey = hashKey;
    }

    @Override
    public void increment(String code, long delta) {
        redisTemplate.opsForHash().increment(hashKey, code, delta);
    }

    @Override
    public long clicks(String code) {
        Object value = redisTemplate.opsForHash().get(hashKey, code);
        if (value == null) {
            return 0;
        }
        return Long.parseLong(value.toString());
    }
}
