package com.company.service.shortlink.infra;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * 两级缓存：L1 Caffeine（进程内）+ L2 共享缓存。
 *
 * <p>【解决什么问题】100 万 QPS 的跳转如果每次都打 Redis，Redis 先成为瓶颈
 * （单实例约 10~20 万 OPS），而且每次请求都要付一次网络往返的延迟。
 * 这个类把"绝大多数请求"截停在进程内的 L1，只让少量 L1 未命中的流量去访问 L2，
 * 从而把 Redis 压力压到容量模型可接受的范围内。
 *
 * <p>【为什么这样设计】为什么短链必须两级：
 * <ul>
 *   <li>L1 命中是纯内存读，单机可以撑到百万级；L2 只承接 L1 未命中的少量流量；</li>
 *   <li>L1 有本地失效延迟，因此只缓存"短时间不一致也能接受"的读多写少数据；</li>
 *   <li>负缓存单独设置更短的 TTL：不存在的短码随时可能被创建，
 *       缓存太久会让"刚创建的短链打不开"；</li>
 *   <li>TTL 加随机抖动：否则同一批 key 会在同一时刻集中过期，
 *       形成周期性的回源尖峰（缓存雪崩的常见诱因）。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】按调用顺序：
 * <ol>
 *   <li>{@link #lookup(String)}：先 L1，再 L2，L2 命中时回填 L1；都没有则返回 MISS；</li>
 *   <li>{@link #put(String, CacheEntry)}：先写 L2（带抖动 TTL），再写 L1；</li>
 *   <li>{@link #evict(String)}：两层同时失效，顺序不影响正确性。</li>
 * </ol>
 * 构造器里 {@code expireAfterRead} 直接返回 {@code currentDuration}，即"读不续期"。
 *
 * <p>【并发安全】Caffeine 的 Cache 本身线程安全，计数器用 LongAdder，
 * 唯一需要留意的是"L1 与 L2 不是原子地一起更新"，因此存在极短的窗口读到旧值 ——
 * 这是两级缓存的固有代价，短链场景可以接受。
 */
public class ShortlinkCache {

    private static final Logger log = LoggerFactory.getLogger(ShortlinkCache.class);

    /**
     * 命中层级。MISS 表示两层都没命中，需要回源。
     */
    public enum Level {
        L1, L2, MISS
    }

    /**
     * 一次查询的结果。
     *
     * @param entry 命中的条目；MISS 时为 null
     * @param level 命中层级
     */
    public record Lookup(CacheEntry entry, Level level) {

        /**
         * @return 是否命中（L1 或 L2）
         */
        public boolean hit() {
            return level != Level.MISS;
        }
    }

    /**
     * 缓存运行统计。
     *
     * @param l1Hits            L1 命中次数
     * @param l2Hits            L2 命中次数
     * @param misses            两层都未命中的次数
     * @param estimatedL1Size   L1 当前估算条目数（Caffeine 的估算值，非精确）
     */
    public record Stats(long l1Hits, long l2Hits, long misses, long estimatedL1Size) {
    }

    /**
     * L1：进程内缓存，容量上限由配置决定，超限按 Caffeine 的近似 LRU 淘汰。
     */
    private final Cache<String, CacheEntry> l1;

    /**
     * L2：跨实例共享，内存或 Redis 实现。
     */
    private final RemoteCache l2;

    /**
     * L1 正常条目的存活时间。
     */
    private final Duration l1Ttl;

    /**
     * L1 负缓存（确认不存在）的存活时间，通常远短于正常 TTL。
     */
    private final Duration l1NegativeTtl;

    /**
     * L2 正常条目的基础存活时间。
     */
    private final Duration l2Ttl;

    /**
     * L2 负缓存的基础存活时间。
     */
    private final Duration l2NegativeTtl;

    /**
     * TTL 抖动比例，范围 [0,1]；0 表示不抖动。
     */
    private final double ttlJitter;

    private final LongAdder l1Hits = new LongAdder();

    private final LongAdder l2Hits = new LongAdder();

    private final LongAdder misses = new LongAdder();

    /**
     * @param l2              L2 实现，不能为 null
     * @param l1MaxSize       L1 最大条目数，必须大于 0
     * @param l1Ttl           L1 正常条目 TTL
     * @param l1NegativeTtl   L1 负缓存 TTL
     * @param l2Ttl           L2 正常条目 TTL
     * @param l2NegativeTtl   L2 负缓存 TTL
     * @param ttlJitter       TTL 抖动比例，必须落在 [0,1]
     * @throws IllegalArgumentException 参数越界时
     */
    public ShortlinkCache(RemoteCache l2,
                          long l1MaxSize,
                          Duration l1Ttl,
                          Duration l1NegativeTtl,
                          Duration l2Ttl,
                          Duration l2NegativeTtl,
                          double ttlJitter) {
        if (l1MaxSize <= 0) {
            throw new IllegalArgumentException("l1MaxSize 必须大于 0: " + l1MaxSize);
        }
        if (ttlJitter < 0 || ttlJitter > 1) {
            throw new IllegalArgumentException("ttlJitter 必须落在 [0,1]: " + ttlJitter);
        }
        this.l2 = l2;
        this.l1Ttl = l1Ttl;
        this.l1NegativeTtl = l1NegativeTtl;
        this.l2Ttl = l2Ttl;
        this.l2NegativeTtl = l2NegativeTtl;
        this.ttlJitter = ttlJitter;
        this.l1 = Caffeine.newBuilder()
                .maximumSize(l1MaxSize)
                .expireAfter(new Expiry<String, CacheEntry>() {
                    @Override
                    public long expireAfterCreate(String key, CacheEntry value, long currentTime) {
                        // 新建时按条目类型选 TTL：负缓存用更短的
                        return l1TtlNanos(value);
                    }

                    @Override
                    public long expireAfterUpdate(String key, CacheEntry value, long currentTime, long currentDuration) {
                        // 被覆盖时重新计时，避免一个旧的长 TTL 残留
                        return l1TtlNanos(value);
                    }

                    @Override
                    public long expireAfterRead(String key, CacheEntry value, long currentTime, long currentDuration) {
                        // 读不续期：否则热点 key 永远不过期，写后失效之外没有兜底
                        return currentDuration;
                    }
                })
                .build();
        log.info("两级缓存初始化: l1MaxSize={}, l1Ttl={}, l1NegativeTtl={}, l2={}, l2Ttl={}, l2NegativeTtl={}, jitter={}",
                l1MaxSize, l1Ttl, l1NegativeTtl, l2.provider(), l2Ttl, l2NegativeTtl, ttlJitter);
    }

    /**
     * 两级查找：L1 → L2。
     *
     * <p>这是跳转热路径：L1 命中时只有一次 ConcurrentHashMap 级别的读，没有 IO。
     * 每次调用都会累加计数器，因此统计接口能看到真实的层级分布。
     *
     * @param code 短码
     * @return 查询结果；两层都未命中时 {@code level == MISS} 且 {@code entry == null}
     */
    public Lookup lookup(String code) {
        CacheEntry local = l1.getIfPresent(code);
        if (local != null) {
            l1Hits.increment();
            return new Lookup(local, Level.L1);
        }
        Optional<CacheEntry> remote = l2.get(code);
        if (remote.isPresent()) {
            l2Hits.increment();
            // 回填 L1：下一次同一个短码的请求就不用再走 L2
            l1.put(code, remote.get());
            log.debug("L2 命中并回填 L1: code={}, negative={}", code, remote.get().negative());
            return new Lookup(remote.get(), Level.L2);
        }
        misses.increment();
        return new Lookup(null, Level.MISS);
    }

    /**
     * 同时写入 L2 和 L1。
     *
     * <p>先写 L2 再写 L1：如果顺序反过来，中间崩溃会留下"L1 有、L2 没有"的条目，
     * 其它实例永远读不到它，而本机又一直命中，问题很难被发现。
     *
     * @param code  短码
     * @param entry 条目；负缓存也要写入，用来挡住穿透
     */
    public void put(String code, CacheEntry entry) {
        // 负缓存必须用更短的 TTL，否则刚创建的短链会被旧的"不存在"挡住
        Duration base = entry.negative() ? l2NegativeTtl : l2Ttl;
        l2.put(code, entry, jitter(base));
        l1.put(code, entry);
    }

    /**
     * 两层同时失效。
     *
     * @param code 短码
     */
    public void evict(String code) {
        l1.invalidate(code);
        l2.evict(code);
    }

    /**
     * @return 当前累计的命中和未命中次数
     */
    public Stats stats() {
        return new Stats(l1Hits.sum(), l2Hits.sum(), misses.sum(), l1.estimatedSize());
    }

    /**
     * @return L2 的实现名，用于统计接口展示
     */
    public String remoteProvider() {
        return l2.provider();
    }

    /**
     * 根据条目类型返回 L1 的 TTL（纳秒）。
     */
    private long l1TtlNanos(CacheEntry entry) {
        Duration ttl = entry.negative() ? l1NegativeTtl : l1Ttl;
        return ttl.toNanos();
    }

    /**
     * 给 TTL 加一个随机的延长量，只加不减。
     *
     * <p>抖动只加不减：宁可多缓存一会儿，也不要提前过期造成同一批 key 一起回源。
     *
     * @param base 基础 TTL
     * @return 加抖动后的 TTL；{@code ttlJitter} 为 0 时原样返回
     */
    private Duration jitter(Duration base) {
        if (ttlJitter <= 0) {
            return base;
        }
        // 额外时长落在 [0, base * ttlJitter) 区间，NextDouble 返回 [0,1)
        long extra = (long) (base.toNanos() * ttlJitter * ThreadLocalRandom.current().nextDouble());
        return Duration.ofNanos(base.toNanos() + extra);
    }
}
