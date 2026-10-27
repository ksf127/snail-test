package com.company.service.shortlink.infra;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * 两级缓存：L1 Caffeine（进程内）+ L2 共享缓存。
 *
 * <p>为什么短链必须两级：
 * <ul>
 *   <li>100 万 QPS 下如果每次都打 Redis，Redis 先成为瓶颈（单实例约 10~20 万 OPS）</li>
 *   <li>L1 命中是纯内存读，单机可以撑到百万级；L2 只承接 L1 未命中的少量流量</li>
 *   <li>L1 有本地失效延迟，因此只缓存"短时间不一致也能接受"的读多写少数据</li>
 * </ul>
 *
 * <p>负缓存单独设置更短的 TTL：不存在的短码随时可能被创建，
 * 缓存太久会让"刚创建的短链打不开"。
 */
public class ShortlinkCache {

    public enum Level {
        L1, L2, MISS
    }

    public record Lookup(CacheEntry entry, Level level) {

        public boolean hit() {
            return level != Level.MISS;
        }
    }

    public record Stats(long l1Hits, long l2Hits, long misses, long estimatedL1Size) {
    }

    private final Cache<String, CacheEntry> l1;

    private final RemoteCache l2;

    private final Duration l1Ttl;

    private final Duration l1NegativeTtl;

    private final Duration l2Ttl;

    private final Duration l2NegativeTtl;

    private final double ttlJitter;

    private final LongAdder l1Hits = new LongAdder();

    private final LongAdder l2Hits = new LongAdder();

    private final LongAdder misses = new LongAdder();

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
                        return l1TtlNanos(value);
                    }

                    @Override
                    public long expireAfterUpdate(String key, CacheEntry value, long currentTime, long currentDuration) {
                        return l1TtlNanos(value);
                    }

                    @Override
                    public long expireAfterRead(String key, CacheEntry value, long currentTime, long currentDuration) {
                        // 读不续期：否则热点 key 永远不过期，写后失效之外没有兜底
                        return currentDuration;
                    }
                })
                .build();
    }

    public Lookup lookup(String code) {
        CacheEntry local = l1.getIfPresent(code);
        if (local != null) {
            l1Hits.increment();
            return new Lookup(local, Level.L1);
        }
        Optional<CacheEntry> remote = l2.get(code);
        if (remote.isPresent()) {
            l2Hits.increment();
            l1.put(code, remote.get());
            return new Lookup(remote.get(), Level.L2);
        }
        misses.increment();
        return new Lookup(null, Level.MISS);
    }

    public void put(String code, CacheEntry entry) {
        Duration base = entry.negative() ? l2NegativeTtl : l2Ttl;
        l2.put(code, entry, jitter(base));
        l1.put(code, entry);
    }

    public void evict(String code) {
        l1.invalidate(code);
        l2.evict(code);
    }

    public Stats stats() {
        return new Stats(l1Hits.sum(), l2Hits.sum(), misses.sum(), l1.estimatedSize());
    }

    public String remoteProvider() {
        return l2.provider();
    }

    private long l1TtlNanos(CacheEntry entry) {
        Duration ttl = entry.negative() ? l1NegativeTtl : l1Ttl;
        return ttl.toNanos();
    }

    /**
     * 抖动只加不减：宁可多缓存一会儿，也不要提前过期造成同一批 key 一起回源。
     */
    private Duration jitter(Duration base) {
        if (ttlJitter <= 0) {
            return base;
        }
        long extra = (long) (base.toNanos() * ttlJitter * ThreadLocalRandom.current().nextDouble());
        return Duration.ofNanos(base.toNanos() + extra);
    }
}
