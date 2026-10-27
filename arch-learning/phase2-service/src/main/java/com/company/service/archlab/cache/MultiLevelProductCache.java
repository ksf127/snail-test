package com.company.service.archlab.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * 多级缓存读路径：L1（Caffeine）→ L2（Redis/内存）→ 数据库。
 *
 * <p>这一版把四个经典问题的处理都放在一条链路上：
 * <ul>
 *   <li><b>击穿</b>：同一个 key 只允许一个线程回源，其余线程排队后读缓存（按 key 加锁 + 双重检查）；</li>
 *   <li><b>穿透</b>：回源为空时按策略写入空值哨兵，避免不存在的 id 每次都打数据库；</li>
 *   <li><b>雪崩</b>：写 L2 的 TTL 加随机抖动，避免一批 key 同时过期；</li>
 *   <li><b>热点</b>：L1 承担绝大部分读，Redis 只承接 L1 未命中的部分。</li>
 * </ul>
 *
 * <p>注意锁的粒度：这里用"按 key 的对象锁"，而不是给整个缓存加一把锁——
 * 后者会把所有 key 串行化，热点场景下吞吐会直接塌掉。
 */
public final class MultiLevelProductCache {

    public enum NullPolicy {
        /** 不缓存空结果：不存在的 id 每次都回源 */
        DISABLED,
        /** 缓存空结果：用短 TTL 的哨兵挡住穿透 */
        CACHE_NULL
    }

    private static final String NULL_SENTINEL = "__NULL__";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Cache<String, Optional<ProductView>> l1;

    private final boolean l1Enabled;

    private final RemoteCache remote;

    private final ProductRepository repository;

    private final String namespace;

    private final Duration l1Ttl;

    private final double jitterRatio;

    private final ConcurrentMap<String, Object> keyLocks = new ConcurrentHashMap<>();

    private final CacheCounters counters = new CacheCounters();

    /**
     * @param l1Enabled  false 表示关闭 L1，用来做"多级 vs 单级"的对照
     * @param l1MaxSize  L1 最大条目数，防止本地堆被"看起来像热点"的长尾 key 撑爆
     * @param jitterRatio TTL 抖动比例，0 表示固定 TTL（雪崩实验的反面样本）
     */
    public MultiLevelProductCache(RemoteCache remote,
                                  ProductRepository repository,
                                  String namespace,
                                  boolean l1Enabled,
                                  long l1MaxSize,
                                  Duration l1Ttl,
                                  double jitterRatio) {
        this.remote = remote;
        this.repository = repository;
        this.namespace = namespace;
        this.l1Ttl = l1Ttl;
        this.jitterRatio = jitterRatio;
        this.l1Enabled = l1Enabled;
        // 关闭 L1 时不能只把 maximumSize 设成 0：Caffeine 的淘汰是异步的，
        // 刚写入的条目在被清理前仍然可读，会污染"单级缓存"的对照组。
        this.l1 = Caffeine.newBuilder()
                .maximumSize(l1Enabled ? l1MaxSize : 1)
                .expireAfterWrite(l1Ttl)
                .build();
    }

    public Optional<ProductView> get(long id, Duration ttl, NullPolicy nullPolicy) {
        String key = namespace + id;

        if (l1Enabled) {
            Optional<ProductView> local = l1.getIfPresent(key);
            if (local != null) {
                counters.l1Hit();
                return record(local);
            }
        }

        Optional<ProductView> fromRemote = readRemote(key);
        if (fromRemote != null) {
            counters.l2Hit();
            if (l1Enabled) {
                l1.put(key, fromRemote);
            }
            return record(fromRemote);
        }

        Object lock = keyLocks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                // 排队期间别人可能已经把值填进来了，必须再检查一次
                if (l1Enabled) {
                    Optional<ProductView> recheckL1 = l1.getIfPresent(key);
                    if (recheckL1 != null) {
                        counters.l1Hit();
                        return record(recheckL1);
                    }
                }
                Optional<ProductView> recheckRemote = readRemote(key);
                if (recheckRemote != null) {
                    counters.l2Hit();
                    if (l1Enabled) {
                        l1.put(key, recheckRemote);
                    }
                    return record(recheckRemote);
                }

                Optional<ProductView> loaded = repository.find(id);
                counters.load();
                if (loaded.isPresent() || nullPolicy == NullPolicy.CACHE_NULL) {
                    remote.put(key, encode(loaded), jitter(ttl));
                    if (l1Enabled) {
                        l1.put(key, loaded);
                    }
                }
                return loaded;
            }
        } finally {
            keyLocks.remove(key, lock);
        }
    }

    public CacheCounters.Snapshot counters() {
        return counters.snapshot();
    }

    public void evict(long id) {
        String key = namespace + id;
        if (l1Enabled) {
            l1.invalidate(key);
        }
        remote.evict(key);
    }

    private Optional<ProductView> readRemote(String key) {
        String raw = remote.get(key);
        if (raw == null) {
            return null;
        }
        if (NULL_SENTINEL.equals(raw)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(raw, ProductView.class));
        } catch (JsonProcessingException e) {
            // 缓存脏数据不应该打挂业务：丢弃并当作未命中
            remote.evict(key);
            return null;
        }
    }

    private Optional<ProductView> record(Optional<ProductView> value) {
        if (value.isEmpty()) {
            counters.nullHit();
        }
        return value;
    }

    private static String encode(Optional<ProductView> value) {
        if (value.isEmpty()) {
            return NULL_SENTINEL;
        }
        try {
            return JSON.writeValueAsString(value.get());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("缓存序列化失败", e);
        }
    }

    private Duration jitter(Duration ttl) {
        if (jitterRatio <= 0) {
            return ttl;
        }
        long base = ttl.toMillis();
        long jittered = Math.round(base * (1 + ThreadLocalRandom.current().nextDouble() * jitterRatio));
        return Duration.ofMillis(jittered);
    }

    Duration l1Ttl() {
        return l1Ttl;
    }
}
