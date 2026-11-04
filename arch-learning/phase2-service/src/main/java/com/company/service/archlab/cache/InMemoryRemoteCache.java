package com.company.service.archlab.cache;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 内存版 L2：用来在没有 Redis 的环境里完整跑通实验。
 *
 * <p>【解决什么问题】多级缓存实验依赖 L2，但本地开发、单元测试、CI 机器上往往没有 Redis。
 * 如果业务代码直接依赖 Redis，这些环境就只能跳过实验，或者被迫起一个真实中间件。
 *
 * <p>【为什么这样设计】它和 {@link RedisRemoteCache} 实现同一个 {@link RemoteCache} 接口，
 * 由配置 {@code arch-lab.cache.remote} 决定注入哪一个（{@code memory} 为默认值）。
 * 用 {@link ConcurrentHashMap} 加"过期时间戳"模拟 Redis 的 TTL：
 * 读的时候判断是否过期，过期就删掉并返回未命中——这已经足够验证缓存逻辑，
 * 但要注意它<b>没有</b> Redis 的淘汰策略、持久化和跨进程共享能力，不能当生产 L2 用。
 *
 * <p>【怎么读这段代码】先看 {@code Entry} 里的 {@code expireAtMillis}，
 * 再看 {@link #put} 如何把 TTL 换算成绝对过期时间，
 * 最后看 {@link #get} 的"读到即判断过期"，理解为什么过期 key 是懒删除而不是后台定时清理。
 *
 * <p><b>日志说明：</b>这个类处在每个缓存请求都会穿过的关键路径上，
 * 这里刻意只用 {@code log.debug}，避免高 QPS 下日志把磁盘打满。
 */
@Component
@ConditionalOnProperty(name = "arch-lab.cache.remote", havingValue = "memory", matchIfMissing = true)
public class InMemoryRemoteCache implements RemoteCache {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRemoteCache.class);

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    /**
     * 读取 L2；命中但已过期时顺手删除。
     *
     * @param key 完整 key
     * @return 命中返回值；未命中或已过期返回 {@code null}
     */
    @Override
    public String get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            log.debug("【L2-内存】未命中 key={}", key);
            return null;
        }
        if (entry.expireAtMillis() <= System.currentTimeMillis()) {
            // 懒删除：没有后台线程扫全表，读到过期条目时再删，避免额外的定时任务开销。
            store.remove(key, entry);
            log.debug("【L2-内存】已过期，删除 key={}", key);
            return null;
        }
        log.debug("【L2-内存】命中 key={}", key);
        return entry.value();
    }

    /**
     * 写入 L2。
     *
     * @param key   完整 key
     * @param value 已序列化的值或空值哨兵
     * @param ttl   过期时间；null/零/负数表示永不过期
     */
    @Override
    public void put(String key, String value, Duration ttl) {
        // 存绝对过期时间而不是 TTL：这样每次读取都能独立判断，不依赖写入时刻。
        long expireAt = ttl == null || ttl.isZero() || ttl.isNegative()
                ? Long.MAX_VALUE
                : System.currentTimeMillis() + ttl.toMillis();
        store.put(key, new Entry(value, expireAt));
        log.debug("【L2-内存】写入 key={}，ttl={}", key, ttl);
    }

    /**
     * 删除 L2 中的 key。
     *
     * @param key 完整 key
     */
    @Override
    public void evict(String key) {
        store.remove(key);
        log.debug("【L2-内存】删除 key={}", key);
    }

    public void clear() {
        store.clear();
        log.debug("【L2-内存】清空全部条目");
    }

    public int size() {
        int size = store.size();
        log.debug("【L2-内存】当前条目数={}", size);
        return size;
    }

    private record Entry(String value, long expireAtMillis) {
    }
}
