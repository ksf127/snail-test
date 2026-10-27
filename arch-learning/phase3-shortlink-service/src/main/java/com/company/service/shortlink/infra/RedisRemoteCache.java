package com.company.service.shortlink.infra;

import com.company.starter.redis.RedisCacheService;
import com.fasterxml.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 版 L2：所有实例共享，重启不丢。
 *
 * <p>序列化走 RedisCacheService 里的 ObjectMapper，key 前缀也统一由它拼，
 * 业务代码看不到 StringRedisTemplate —— 这是本项目所有服务的硬约束。
 */
public class RedisRemoteCache implements RemoteCache {

    private static final TypeReference<CacheEntry> ENTRY_TYPE = new TypeReference<>() {
    };

    private final RedisCacheService cache;

    public RedisRemoteCache(RedisCacheService cache) {
        this.cache = cache;
    }

    @Override
    public Optional<CacheEntry> get(String code) {
        return Optional.ofNullable(cache.get(key(code), ENTRY_TYPE));
    }

    @Override
    public void put(String code, CacheEntry entry, Duration ttl) {
        cache.put(key(code), entry, ttl);
    }

    @Override
    public void evict(String code) {
        cache.evict(key(code));
    }

    @Override
    public String provider() {
        return "redis";
    }

    private String key(String code) {
        return "shortlink:link:" + code;
    }
}
