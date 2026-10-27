package com.company.service.archlab.cache;

import java.time.Duration;

import com.company.starter.redis.CompanyRedisProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 版 L2：直接用 {@link StringRedisTemplate} 存原始字符串。
 *
 * <p>为什么不复用 {@code RedisCacheService}：它把"空值"折叠成了 {@code null}，
 * 而 L2 层必须区分"未命中"和"命中一个缓存的空值"——前者要回源，后者不能。
 * 生产里更合适的做法是给 Starter 增加 raw 读写能力，这里先保持实验代码自洽。
 */
@Component
@ConditionalOnProperty(name = "arch-lab.cache.remote", havingValue = "redis")
public class RedisRemoteCache implements RemoteCache {

    private final StringRedisTemplate redisTemplate;

    private final CompanyRedisProperties properties;

    public RedisRemoteCache(StringRedisTemplate redisTemplate, CompanyRedisProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public String get(String key) {
        return redisTemplate.opsForValue().get(realKey(key));
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            redisTemplate.opsForValue().set(realKey(key), value);
        } else {
            redisTemplate.opsForValue().set(realKey(key), value, ttl);
        }
    }

    @Override
    public void evict(String key) {
        redisTemplate.delete(realKey(key));
    }

    private String realKey(String key) {
        String prefix = properties.getKeyPrefix();
        return prefix == null || prefix.isEmpty() ? key : prefix + key;
    }
}
