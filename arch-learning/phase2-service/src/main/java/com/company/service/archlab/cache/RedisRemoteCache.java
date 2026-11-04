package com.company.service.archlab.cache;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.company.starter.redis.CompanyRedisProperties;

/**
 * Redis 版 L2：直接用 {@link StringRedisTemplate} 存原始字符串。
 *
 * <p>【解决什么问题】内存版 L2 只在单进程内有效，多个实例各存一份，
 * 一台机器写入、另一台机器看不到，缓存一致性和命中率都会失真。
 * 这个实现把 L2 放到 Redis，所有实例共享同一份数据，才是生产形态的二级缓存。
 *
 * <p>【为什么这样设计】用 {@code StringRedisTemplate} 做"原样存取"，不在这一层做序列化和空值折叠：
 * 上层 {@link MultiLevelProductCache} 必须能区分"未命中（要回源）"和"命中一个空值（不能回源）"，
 * 如果复用会把空值折叠成 {@code null} 的封装，穿透治理就失效了。
 * 另外给所有 key 统一加上配置里的前缀，避免多个业务/多个环境共用 Redis 时串 key。
 *
 * <p>【怎么读这段代码】先看 {@link #realKey(String)} 的前缀拼接，
 * 再看 {@link #put} 对 TTL 的分支：有 TTL 用带过期的 set，无 TTL 用普通 set，
 * 两者对应 Redis 的 {@code SET key value EX ttl} 与 {@code SET key value}。
 *
 * <p><b>日志说明：</b>这个类处在每个缓存请求都会穿过的关键路径上，
 * 这里刻意只用 {@code log.debug}，避免高 QPS 下日志把磁盘打满。
 *
 * <p>为什么不复用 {@code RedisCacheService}：它把"空值"折叠成了 {@code null}，
 * 而 L2 层必须区分"未命中"和"命中一个缓存的空值"——前者要回源，后者不能。
 * 生产里更合适的做法是给 Starter 增加 raw 读写能力，这里先保持实验代码自洽。
 */
@Component
@ConditionalOnProperty(name = "arch-lab.cache.remote", havingValue = "redis")
public class RedisRemoteCache implements RemoteCache {

    private static final Logger log = LoggerFactory.getLogger(RedisRemoteCache.class);

    private final StringRedisTemplate redisTemplate;

    private final CompanyRedisProperties properties;

    public RedisRemoteCache(StringRedisTemplate redisTemplate, CompanyRedisProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 读取 Redis。
     *
     * @param key 业务 key（不带前缀）
     * @return 命中返回值；未命中返回 {@code null}
     */
    @Override
    public String get(String key) {
        String value = redisTemplate.opsForValue().get(realKey(key));
        log.debug("【L2-Redis】get key={}，{}", realKey(key), value == null ? "未命中" : "命中");
        return value;
    }

    /**
     * 写入 Redis。
     *
     * @param key   业务 key（不带前缀）
     * @param value 已序列化的值或空值哨兵
     * @param ttl   过期时间；null/零/负数表示永不过期
     */
    @Override
    public void put(String key, String value, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            // 不带 TTL 的 key 不会自动过期，只适合明确要长期保留的数据；
            // 缓存场景应尽量走下面带 TTL 的分支，否则容易留下永久脏数据。
            redisTemplate.opsForValue().set(realKey(key), value);
        } else {
            redisTemplate.opsForValue().set(realKey(key), value, ttl);
        }
        log.debug("【L2-Redis】put key={}，ttl={}", realKey(key), ttl);
    }

    /**
     * 删除 Redis 中的 key。
     *
     * @param key 业务 key（不带前缀）
     */
    @Override
    public void evict(String key) {
        redisTemplate.delete(realKey(key));
        log.debug("【L2-Redis】evict key={}", realKey(key));
    }

    /** 统一加前缀，把本实验的 key 与其他业务隔离开。 */
    private String realKey(String key) {
        String prefix = properties.getKeyPrefix();
        return prefix == null || prefix.isEmpty() ? key : prefix + key;
    }
}
