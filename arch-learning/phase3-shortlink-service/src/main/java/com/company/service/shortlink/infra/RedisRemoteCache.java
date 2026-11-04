package com.company.service.shortlink.infra;

import com.company.starter.redis.RedisCacheService;
import com.fasterxml.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 版 L2 缓存：所有实例共享，重启不丢。
 *
 * <p>【解决什么问题】L1 是进程内缓存，实例之间互不可见，重启即空。
 * 在 100 万 QPS 下，L1 不可能装下所有热点短码，未命中的部分必须有一层共享存储兜住，
 * 否则每次 L1 未命中都会直接打到数据库。这个类就是那层共享 L2。
 *
 * <p>【为什么这样设计】
 * <ul>
 *   <li>序列化与 key 前缀统一交给 {@code RedisCacheService}，业务代码不直接接触
 *       StringRedisTemplate —— 这是本项目所有服务的硬约束，避免各服务各写一套 key 规则；</li>
 *   <li>缓存的是 {@link CacheEntry} 而不是 {@code Shortlink}：负缓存（确认不存在）
 *       必须能被缓存，否则不存在的短码会反复穿透；</li>
 *   <li>只缓存"短时间不一致也能接受"的数据：短链读多写极少，天然适合。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】key 只在这里拼一次（{@link #key(String)}），
 * 读写删都走它。{@link #get} 用 {@link Optional} 表达"未命中"，
 * 与负缓存（命中但 entry.negative=true）是两件不同的事，不要混淆。
 * 无状态，线程安全。
 */
public class RedisRemoteCache implements RemoteCache {

    /**
     * 反序列化目标类型。必须显式给出泛型，否则 Jackson 会把 JSON 解析成 LinkedHashMap。
     */
    private static final TypeReference<CacheEntry> ENTRY_TYPE = new TypeReference<>() {
    };

    private final RedisCacheService cache;

    public RedisRemoteCache(RedisCacheService cache) {
        this.cache = cache;
    }

    /**
     * 读取 L2。
     *
     * @param code 短码
     * @return 命中时返回缓存条目（可能是负缓存）；未命中返回空
     */
    @Override
    public Optional<CacheEntry> get(String code) {
        return Optional.ofNullable(cache.get(key(code), ENTRY_TYPE));
    }

    /**
     * 写入 L2。
     *
     * @param code  短码
     * @param entry 缓存条目
     * @param ttl   存活时间（上层已按正常/负缓存区分）
     */
    @Override
    public void put(String code, CacheEntry entry, Duration ttl) {
        cache.put(key(code), entry, ttl);
    }

    /**
     * 主动删除 L2 条目（删除短链时调用）。
     *
     * @param code 短码
     */
    @Override
    public void evict(String code) {
        cache.evict(key(code));
    }

    /**
     * @return 固定返回 "redis"，用于统计接口展示当前 L2 实现
     */
    @Override
    public String provider() {
        return "redis";
    }

    /**
     * 统一的 key 生成规则；集中在这里，避免读写删用了不同前缀导致缓存永不一致。
     */
    private String key(String code) {
        return "shortlink:link:" + code;
    }
}
