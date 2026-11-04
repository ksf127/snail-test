package com.company.service.archlab.cache;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 缓存四类经典问题的可运行实验台：穿透、击穿、雪崩、热点。
 *
 * <p>【解决什么问题】真实业务里缓存失效往往不是"缓存挂了"，而是四种特定流量模式：
 * <ul>
 *   <li><b>穿透</b>：查一个不存在的 id，缓存永远不命中，每次都打数据库；</li>
 *   <li><b>击穿</b>：单个热点 key 刚好过期，并发线程一起回源；</li>
 *   <li><b>雪崩</b>：大批 key 同时过期，回源流量叠加；</li>
 *   <li><b>热点</b>：90% 的流量集中在极少数 key 上，单机或单分片被打满。</li>
 * </ul>
 * 不加防护时，数据库会在几秒内被回源流量压垮，表现为接口超时、连接池耗尽，甚至整站不可用。
 *
 * <p>【为什么这样设计】每个方法都同时提供"出问题的写法"和"治理后的写法"，
 * 并且用独立的 namespace 和独立的 {@link ProductRepository}，保证两次实验互不污染。
 * 返回值里带数据库回源次数，这样结论不靠感觉，而靠 {@code dbLoads} 这个数字。
 * 并发类实验用 {@link CyclicBarrier} 让线程同时起跑，否则线程池会串行执行，根本形不成竞争。
 *
 * <p>【怎么读这段代码】建议按问题分组对照阅读：
 * <ol>
 *   <li>{@link #penetration}：看 NAIVE / NULL_CACHE / BLOOM 三种模式的 {@code dbLoads}；</li>
 *   <li>{@link #breakdown}：看 NAIVE 与 SINGLE_FLIGHT 的 {@code dbLoads} 差异；</li>
 *   <li>{@link #avalanche}：看 {@code jitter} 为 true/false 时过期后的 {@code reloads}；</li>
 *   <li>{@link #hotKey}：看 L1 开关对 {@code l1Hits}/{@code l2Hits} 的影响。</li>
 * </ol>
 * 每个实验入口都会打一条 info 日志说明策略与结论，可据此核对返回值。
 */
@Service
public class CacheLab {

    private static final Logger log = LoggerFactory.getLogger(CacheLab.class);

    /** 数据库里不存在的 id，用来模拟穿透流量。 */
    private static final long MISSING_ID = 999_999L;

    /** 实验用 TTL：足够长，保证一轮实验内不会自然过期，避免干扰命中统计。 */
    private static final Duration LAB_TTL = Duration.ofMinutes(5);

    private final RemoteCache remoteCache;

    public CacheLab(RemoteCache remoteCache) {
        this.remoteCache = remoteCache;
    }

    public enum PenetrationMode {
        /** 不存在的 id 每次都打数据库 */
        NAIVE,
        /** 空结果也缓存（短 TTL 哨兵） */
        NULL_CACHE,
        /** 布隆过滤器提前拒绝 */
        BLOOM
    }

    /**
     * 穿透实验结果。
     *
     * @param mode         本次使用的策略
     * @param requests     发起的请求数
     * @param dbLoads      真正打到数据库的次数，越小越好
     * @param bloomRejected 被布隆过滤器提前拒绝的次数
     * @param elapsedMillis 实验总耗时
     */
    public record PenetrationResult(String mode,
                                    int requests,
                                    long dbLoads,
                                    long bloomRejected,
                                    long elapsedMillis) {
    }

    public enum BreakdownMode {
        /** 缓存失效瞬间所有并发线程一起回源 */
        NAIVE,
        /** 同一个 key 只放一个线程回源 */
        SINGLE_FLIGHT
    }

    public record BreakdownResult(String mode, int threads, long dbLoads, long elapsedMillis) {
    }

    public record AvalancheResult(boolean jitter, int keys, long reloads, long elapsedMillis) {
    }

    public record HotKeyResult(boolean l1Enabled,
                               int requests,
                               long l1Hits,
                               long l2Hits,
                               long dbLoads,
                               double l1HitRate,
                               long elapsedMillis) {
    }

    /**
     * 穿透：用不存在的 id 连续查 {@code requests} 次，比较三种写法打库的次数。
     *
     * <p>真实业务里穿透来自"查不存在的商品/用户"的脏参数或爬虫构造的随机 id。
     * NAIVE 每次都打库；NULL_CACHE 第一次把空结果写成哨兵，后续请求命中空值；
     * BLOOM 用位图提前判定"一定不存在"，连缓存都不用查。
     *
     * @param requests 请求次数，范围 [1, 100000]
     * @param mode     防护策略
     * @return 三种策略下的回源次数与布隆拦截次数
     * @throws IllegalArgumentException 参数越界或 mode 为空时抛出
     */
    public PenetrationResult penetration(int requests, PenetrationMode mode) {
        requireBetween("requests", requests, 1, 100_000);
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空");
        }

        log.info("【穿透实验】开始，requests={}，mode={}，查询不存在的 id={}", requests, mode, MISSING_ID);
        // 每个实验独立造一个"数据库"，回源计数只统计本次实验。
        ProductRepository repository = new ProductRepository(0);
        long start = System.nanoTime();
        long rejected = 0;

        switch (mode) {
            case NAIVE -> {
                // 最朴素的写法：不查缓存，直接打库。回源次数应该等于 requests。
                for (int i = 0; i < requests; i++) {
                    repository.find(MISSING_ID);
                }
            }
            case NULL_CACHE -> {
                // 第一次回源后把空值哨兵写入缓存，后续请求命中空值就不再打库。
                MultiLevelProductCache cache = newCache(repository, true, 1024, LAB_TTL, 0);
                for (int i = 0; i < requests; i++) {
                    cache.get(MISSING_ID, LAB_TTL, MultiLevelProductCache.NullPolicy.CACHE_NULL);
                }
            }
            case BLOOM -> {
                // 先用"真实存在的 id"预热布隆过滤器，再拿不存在的 id 去问。
                BloomFilter filter = new BloomFilter(256, 0.001);
                repository.existingIds().forEach(filter::add);
                for (int i = 0; i < requests; i++) {
                    if (!filter.mightContain(MISSING_ID)) {
                        // 返回 false 是确定性的"一定不存在"，可以安全拦截，不会误杀真实数据。
                        rejected++;
                        continue;
                    }
                    repository.find(MISSING_ID);
                }
            }
        }

        PenetrationResult result = new PenetrationResult(mode.name(), requests,
                repository.loadCount(), rejected, elapsed(start));
        log.info("【穿透实验】结束，mode={}，请求={}，回源 DB={} 次，布隆拦截={} 次，" +
                        "结论：{}",
                mode, requests, result.dbLoads(), result.bloomRejected(), penetrationConclusion(mode));
        return result;
    }

    /**
     * 击穿：缓存刚失效时，{@code threads} 个线程同时读同一个 key。
     *
     * <p>真实业务里击穿发生在秒杀商品、首页配置这类热点 key 过期的瞬间：
     * 所有请求都发现缓存没了，于是一起回源。NAIVE 的 {@code dbLoads} 会接近线程数，
     * SINGLE_FLIGHT 则只有一个线程真正查库，其余线程排队后直接拿到它填好的缓存。
     *
     * @param threads    并发线程数，范围 [1, 64]
     * @param loadMillis 模拟单次查库耗时（毫秒），范围 [0, 1000]，值越大竞态越明显
     * @param mode       是否启用单飞
     * @return 实际回源次数与总耗时
     * @throws IllegalArgumentException 参数越界或 mode 为空时抛出
     */
    public BreakdownResult breakdown(int threads, long loadMillis, BreakdownMode mode) {
        requireBetween("threads", threads, 1, 64);
        requireBetween("loadMillis", loadMillis, 0, 1_000);
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空");
        }

        log.info("【击穿实验】开始，threads={}，loadMillis={}，mode={}", threads, loadMillis, mode);
        ProductRepository repository = new ProductRepository(loadMillis);
        MultiLevelProductCache cache = newCache(repository, true, 1024, LAB_TTL, 0);

        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long start = System.nanoTime();
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        // 所有线程在栅栏处对齐，保证同一瞬间冲缓存，复现"同时未命中"。
                        barrier.await(5, TimeUnit.SECONDS);
                        if (mode == BreakdownMode.SINGLE_FLIGHT) {
                            // 走多级缓存：同一个 key 的并发回源会被按 key 锁收敛成一个。
                            cache.get(1L, LAB_TTL, MultiLevelProductCache.NullPolicy.DISABLED);
                        } else {
                            // 对照组绕过缓存，直接查库，等价于"缓存刚失效且没有防护"。
                            repository.find(1L);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        throw new IllegalStateException("并发读实验失败", e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            awaitQuietly(done);
        } finally {
            pool.shutdownNow();
        }
        BreakdownResult result = new BreakdownResult(mode.name(), threads,
                repository.loadCount(), elapsed(start));
        log.info("【击穿实验】结束，mode={}，并发={}，回源 DB={} 次，耗时={}ms。" +
                        "SINGLE_FLIGHT 下应接近 1 次；NAIVE 下接近并发数",
                mode, threads, result.dbLoads(), result.elapsedMillis());
        return result;
    }

    /**
     * 雪崩：预热 {@code keys} 个 key 后等它们同时过期，比较固定 TTL 与抖动 TTL 的重回源数量。
     *
     * <p>真实业务里雪崩常来自"批量预热时用了同一个 TTL"：预热完成的那一刻很整齐，
     * 过期的那一刻也很整齐，于是所有 key 在同一秒失效、同一秒回源。
     * 抖动 TTL 把过期时刻打散，回源就被摊平到一段时间里。
     *
     * <p>L1 在这里被关闭，确保每次读都会真的落到 L2，否则本地缓存会掩盖过期问题。
     *
     * @param keys          预热的 key 数量，范围 [1, 50]
     * @param baseTtlMillis 基础 TTL（毫秒），范围 [50, 5000]
     * @param jitter        true 表示给 TTL 加 0~25% 的随机抖动
     * @return 过期后重新回源的次数
     * @throws IllegalArgumentException 参数越界时抛出
     */
    public AvalancheResult avalanche(int keys, long baseTtlMillis, boolean jitter) {
        requireBetween("keys", keys, 1, 50);
        requireBetween("baseTtlMillis", baseTtlMillis, 50, 5_000);

        log.info("【雪崩实验】开始，keys={}，baseTtlMillis={}，jitter={}", keys, baseTtlMillis, jitter);
        ProductRepository repository = new ProductRepository(0);
        // L1 关闭、L1 TTL 设成 1ms：目的就是让每次读都穿透到 L2，观察 key 是否真的过期。
        MultiLevelProductCache cache = newCache(repository, false, 0, Duration.ofMillis(1), jitter ? 0.25 : 0);

        List<Long> ids = repository.existingIds().limit(keys).boxed().toList();
        Duration ttl = Duration.ofMillis(baseTtlMillis);

        long start = System.nanoTime();
        // 预热：第一次读必然回源，这一步的计数要单独记下来，不算在"过期后回源"里。
        for (long id : ids) {
            cache.get(id, ttl, MultiLevelProductCache.NullPolicy.DISABLED);
        }
        long afterWarmup = repository.loadCount();

        // 等到超过基础 TTL：固定 TTL 的 key 会全部同时过期；
        // 抖动后的 key 过期时刻被拉开，这里仍会有部分已经过期。
        sleep(baseTtlMillis + 10);

        for (long id : ids) {
            cache.get(id, ttl, MultiLevelProductCache.NullPolicy.DISABLED);
        }
        long reloads = repository.loadCount() - afterWarmup;
        AvalancheResult result = new AvalancheResult(jitter, keys, reloads, elapsed(start));
        log.info("【雪崩实验】结束，jitter={}，key 数={}，过期后回源 DB={} 次，耗时={}ms。" +
                        "抖动越大，回源越分散",
                jitter, keys, reloads, result.elapsedMillis());
        return result;
    }

    /**
     * 热点：90% 的请求打同一个 key，10% 打其余 49 个 key，比较有无 L1 时的命中结构。
     *
     * <p>真实业务里热点可能是秒杀商品、明星微博、首页配置。
     * 打开 L1 时，90% 的流量在进程内就被消化；关闭 L1 时，这些流量全部压到 L2，
     * L2 的带宽和单分片能力就成了瓶颈。
     *
     * @param requests  请求次数，范围 [1, 200000]
     * @param l1Enabled 是否开启本地缓存
     * @return L1/L2 命中数、回源数与 L1 命中率
     * @throws IllegalArgumentException 请求数越界时抛出
     */
    public HotKeyResult hotKey(int requests, boolean l1Enabled) {
        requireBetween("requests", requests, 1, 200_000);

        log.info("【热点实验】开始，requests={}，l1Enabled={}", requests, l1Enabled);
        ProductRepository repository = new ProductRepository(0);
        MultiLevelProductCache cache = newCache(repository, l1Enabled, 1024, LAB_TTL, 0);

        long start = System.nanoTime();
        for (int i = 0; i < requests; i++) {
            // 每 10 个请求里 9 个打 id=1（热点），剩下 1 个分散到 2~50，模拟真实的长尾。
            long id = i % 10 < 9 ? 1L : 2L + (i % 49);
            cache.get(id, LAB_TTL, MultiLevelProductCache.NullPolicy.DISABLED);
        }
        CacheCounters.Snapshot snapshot = cache.counters();
        HotKeyResult result = new HotKeyResult(l1Enabled,
                requests,
                snapshot.l1Hits(),
                snapshot.l2Hits(),
                snapshot.loads(),
                snapshot.l1HitRate(),
                elapsed(start));
        log.info("【热点实验】结束，l1Enabled={}，请求={}，L1 命中={}，L2 命中={}，回源 DB={}，" +
                        "L1 命中率={}，耗时={}ms",
                l1Enabled, requests, result.l1Hits(), result.l2Hits(), result.dbLoads(),
                String.format("%.2f", result.l1HitRate()), result.elapsedMillis());
        return result;
    }

    /**
     * 每次实验用独立 namespace：L2 是共享的 Spring Bean，不隔离会读到上一次实验的数据。
     */
    private MultiLevelProductCache newCache(ProductRepository repository,
                                            boolean l1Enabled,
                                            long l1MaxSize,
                                            Duration l1Ttl,
                                            double jitterRatio) {
        return new MultiLevelProductCache(remoteCache,
                repository,
                "lab:" + System.nanoTime() + ":",
                l1Enabled,
                l1MaxSize,
                l1Ttl,
                jitterRatio);
    }

    private static String penetrationConclusion(PenetrationMode mode) {
        return switch (mode) {
            case NAIVE -> "没有防护，每个不存在的 id 都打一次库";
            case NULL_CACHE -> "空值哨兵让后续请求在缓存层就被挡住";
            case BLOOM -> "布隆过滤器在缓存之前拦截，位图判断不会误杀真实存在的数据";
        };
    }

    private static long elapsed(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发读实验超时");
            }
        } catch (InterruptedException e) {
            // 恢复中断标记，避免上层线程池感知不到停止信号。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发读实验被中断", e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("实验等待被中断", e);
        }
    }

    private static void requireBetween(String name, long value, long min, long max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " 必须在 [" + min + ", " + max + "] 之间");
        }
    }
}
