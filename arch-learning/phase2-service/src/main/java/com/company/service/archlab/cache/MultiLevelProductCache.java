package com.company.service.archlab.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * 多级缓存读路径：L1（Caffeine）→ L2（Redis/内存）→ 数据库。
 *
 * <p>【解决什么问题】只用 Redis 做缓存时，每个请求都要一次网络往返，热点 key 还会把某个分片的
 * 带宽和 CPU 打满。更麻烦的是四类经典故障：
 * <ul>
 *   <li><b>穿透</b>：查一个数据库里不存在的 id，缓存永远不命中，每次都回源；</li>
 *   <li><b>击穿</b>：某个热点 key 刚过期，大量并发线程同时发现未命中，一起回源，数据库瞬间被压垮；</li>
 *   <li><b>雪崩</b>：一大批 key 设置了相同的 TTL，同时过期，回源流量在同一秒叠加；</li>
 *   <li><b>热点</b>：少数 key 承担绝大部分流量，单机/单分片成为瓶颈。</li>
 * </ul>
 *
 * <p>【为什么这样设计】两级缓存是"本地速度 + 全局容量"的折中：
 * L1 是本进程堆内的 Caffeine，读一次是纳秒级，专门挡热点；L2 是 Redis/内存实现，
 * 容量大且多实例共享，负责兜底和跨实例一致。如果只用 Redis，热点 key 的每次读都要走网络；
 * 如果只用本地缓存，多实例之间会不一致、容量也受单机堆限制。
 * 四个问题的处理都放在同一条链路上：
 * <ul>
 *   <li><b>击穿</b>：同一个 key 只允许一个线程回源，其余线程排队后读缓存（按 key 加锁 + 双重检查）；</li>
 *   <li><b>穿透</b>：回源为空时按策略写入空值哨兵，避免不存在的 id 每次都打数据库；</li>
 *   <li><b>雪崩</b>：写 L2 的 TTL 加随机抖动，避免一批 key 同时过期；</li>
 *   <li><b>热点</b>：L1 承担绝大部分读，Redis 只承接 L1 未命中的部分。</li>
 * </ul>
 *
 * <p>注意锁的粒度：这里用"按 key 的对象锁"，而不是给整个缓存加一把锁——
 * 后者会把所有 key 串行化，热点场景下吞吐会直接塌掉。
 *
 * <p>【怎么读这段代码】按 {@link #get} 的执行顺序读：
 * 先 L1，未命中再 L2；L2 也没有时，才按 key 取锁进入回源区，并在锁内<b>再查一遍</b>
 * （排队期间别的线程可能已经把值填好了）；最后查库、写 L2、回填 L1。
 * 看实验结论时不要只盯耗时，要看 {@link CacheCounters} 里的 L1/L2 命中与回源次数，
 * 日志则按 {@code L1 命中 / L2 命中 / 回源 DB} 三个关键字过滤。
 *
 * <p><b>日志说明：</b>{@link #get} 是每次业务读都会走的路径，
 * 命中相关日志刻意用 {@code log.debug}，避免高 QPS 下日志把磁盘打满；
 * 只有反序列化失败这种异常/降级分支才用 {@code log.warn}。
 */
public final class MultiLevelProductCache {

    private static final Logger log = LoggerFactory.getLogger(MultiLevelProductCache.class);

    public enum NullPolicy {
        /** 不缓存空结果：不存在的 id 每次都回源 */
        DISABLED,
        /** 缓存空结果：用短 TTL 的哨兵挡住穿透 */
        CACHE_NULL
    }

    /**
     * 空值哨兵：L2 只能存字符串，"没查到"必须用一个特殊值表示。
     * 不能直接存空串，因为空串可能是合法业务值；也不能不存，否则又退化成穿透。
     */
    private static final String NULL_SENTINEL = "__NULL__";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Cache<String, Optional<ProductView>> l1;

    private final boolean l1Enabled;

    private final RemoteCache remote;

    private final ProductRepository repository;

    private final String namespace;

    private final Duration l1Ttl;

    private final double jitterRatio;

    /** 每个 key 一把锁；只在回源时创建，用完移除，避免 key 无限增长把内存撑爆。 */
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

    /**
     * 多级缓存的核心读路径。
     *
     * @param id         商品 id
     * @param ttl        写入 L2 时的基础过期时间
     * @param nullPolicy 回源为空时是否写入空值哨兵；穿透治理要靠它
     * @return 命中或回源得到的商品；数据确实不存在时返回 {@link Optional#empty()}
     */
    public Optional<ProductView> get(long id, Duration ttl, NullPolicy nullPolicy) {
        String key = namespace + id;

        // 第一站：L1。热点 key 在这里就被挡掉，连网络都不用走。
        if (l1Enabled) {
            Optional<ProductView> local = l1.getIfPresent(key);
            if (local != null) {
                counters.l1Hit();
                log.debug("【多级缓存】L1 命中 id={}", id);
                return record(local);
            }
        }

        // 第二站：L2。L1 没有（或关闭）时才走这里，通常是一次网络往返。
        Optional<ProductView> fromRemote = readRemote(key);
        if (fromRemote != null) {
            counters.l2Hit();
            log.debug("【多级缓存】L2 命中 id={}，l1Enabled={}", id, l1Enabled);
            if (l1Enabled) {
                l1.put(key, fromRemote);
            }
            return record(fromRemote);
        }

        // 只有 L1/L2 都没有，才进入"回源"这个昂贵的分支。
        Object lock = keyLocks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                log.debug("【多级缓存】L1/L2 均未命中，进入回源锁 id={}", id);
                // 排队期间别人可能已经把值填进来了，必须再检查一次：
                // 否则每个排队的线程都会各自查一次库，单飞（single-flight）就失效了。
                if (l1Enabled) {
                    Optional<ProductView> recheckL1 = l1.getIfPresent(key);
                    if (recheckL1 != null) {
                        counters.l1Hit();
                        log.debug("【多级缓存】排队后复查 L1 命中 id={}", id);
                        return record(recheckL1);
                    }
                }
                Optional<ProductView> recheckRemote = readRemote(key);
                if (recheckRemote != null) {
                    counters.l2Hit();
                    log.debug("【多级缓存】排队后复查 L2 命中 id={}", id);
                    if (l1Enabled) {
                        l1.put(key, recheckRemote);
                    }
                    return record(recheckRemote);
                }

                Optional<ProductView> loaded = repository.find(id);
                counters.load();
                log.debug("【多级缓存】回源 DB id={}，结果={}", id, loaded.isPresent() ? "存在" : "不存在");
                // 查到值必须回填；没查到值只有在 CACHE_NULL 策略下才回填哨兵——
                // 这就是"穿透"实验里 NAIVE 与 NULL_CACHE 的唯一区别。
                if (loaded.isPresent() || nullPolicy == NullPolicy.CACHE_NULL) {
                    remote.put(key, encode(loaded), jitter(ttl));
                    if (l1Enabled) {
                        l1.put(key, loaded);
                    }
                }
                return loaded;
            }
        } finally {
            // 锁对象只在回源期间存在，用完立刻移除，防止大量不同 key 把 keyLocks 越堆越大。
            keyLocks.remove(key, lock);
        }
    }

    /** @return 当前累计的命中/回源统计快照 */
    public CacheCounters.Snapshot counters() {
        return counters.snapshot();
    }

    /**
     * 删除缓存（一致性实验的写路径会用到）。
     *
     * <p>先删 L1 再删 L2：如果顺序反过来，删完 L2 到删 L1 之间，
     * 其他线程可能从 L2 回源旧值再填回 L1，让 L1 长期驻留脏数据。
     *
     * @param id 商品 id
     */
    public void evict(long id) {
        String key = namespace + id;
        if (l1Enabled) {
            l1.invalidate(key);
        }
        remote.evict(key);
        log.debug("【多级缓存】删除缓存 id={}", id);
    }

    /**
     * 读 L2 并反序列化。
     *
     * @param key 完整 key
     * @return {@code null} 表示未命中（需要回源）；{@link Optional#empty()} 表示命中空值哨兵
     */
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
            // 缓存脏数据不应该打挂业务：丢弃并当作未命中。
            // 常见来源是缓存对象加了字段或改了类型，旧 JSON 无法反序列化。
            log.warn("【多级缓存】L2 反序列化失败，丢弃该 key 并按未命中处理 key={}", key, e);
            remote.evict(key);
            return null;
        }
    }

    /** 命中空值哨兵时单独记一笔，方便判断穿透治理是否真的生效。 */
    private Optional<ProductView> record(Optional<ProductView> value) {
        if (value.isEmpty()) {
            counters.nullHit();
        }
        return value;
    }

    /** 把 Optional 编码成 L2 里的字符串；空值统一写成哨兵。 */
    private static String encode(Optional<ProductView> value) {
        if (value.isEmpty()) {
            return NULL_SENTINEL;
        }
        try {
            return JSON.writeValueAsString(value.get());
        } catch (JsonProcessingException e) {
            // 序列化失败说明缓存对象本身有问题，继续写会导致 L2 里出现坏数据。
            log.warn("【多级缓存】缓存序列化失败，放弃写入 L2", e);
            throw new IllegalStateException("缓存序列化失败", e);
        }
    }

    /**
     * 给 TTL 加随机抖动，打散同一批 key 的过期时刻。
     *
     * <p>抖动只会让过期时间变长（最多 +jitterRatio），不会提前过期，
     * 所以不会降低缓存命中率，只是把"同时失效"变成"错峰失效"。
     */
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
