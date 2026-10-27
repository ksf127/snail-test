package com.company.service.archlab.cache;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

/**
 * 缓存四类经典问题的可运行实验台：穿透、击穿、雪崩、热点。
 *
 * <p>每个方法都同时提供"出问题的写法"和"治理后的写法"，返回值里带数据库回源次数，
 * 这样结论不靠感觉，而靠 {@code dbLoads} 这个数字。
 */
@Service
public class CacheLab {

    private static final long MISSING_ID = 999_999L;

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
     */
    public PenetrationResult penetration(int requests, PenetrationMode mode) {
        requireBetween("requests", requests, 1, 100_000);
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空");
        }

        ProductRepository repository = new ProductRepository(0);
        long start = System.nanoTime();
        long rejected = 0;

        switch (mode) {
            case NAIVE -> {
                for (int i = 0; i < requests; i++) {
                    repository.find(MISSING_ID);
                }
            }
            case NULL_CACHE -> {
                MultiLevelProductCache cache = newCache(repository, true, 1024, LAB_TTL, 0);
                for (int i = 0; i < requests; i++) {
                    cache.get(MISSING_ID, LAB_TTL, MultiLevelProductCache.NullPolicy.CACHE_NULL);
                }
            }
            case BLOOM -> {
                BloomFilter filter = new BloomFilter(256, 0.001);
                repository.existingIds().forEach(filter::add);
                for (int i = 0; i < requests; i++) {
                    if (!filter.mightContain(MISSING_ID)) {
                        rejected++;
                        continue;
                    }
                    repository.find(MISSING_ID);
                }
            }
        }

        return new PenetrationResult(mode.name(), requests, repository.loadCount(), rejected, elapsed(start));
    }

    /**
     * 击穿：缓存刚失效时，{@code threads} 个线程同时读同一个 key。
     */
    public BreakdownResult breakdown(int threads, long loadMillis, BreakdownMode mode) {
        requireBetween("threads", threads, 1, 64);
        requireBetween("loadMillis", loadMillis, 0, 1_000);
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空");
        }

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
                        barrier.await(5, TimeUnit.SECONDS);
                        if (mode == BreakdownMode.SINGLE_FLIGHT) {
                            cache.get(1L, LAB_TTL, MultiLevelProductCache.NullPolicy.DISABLED);
                        } else {
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
        return new BreakdownResult(mode.name(), threads, repository.loadCount(), elapsed(start));
    }

    /**
     * 雪崩：预热 {@code keys} 个 key 后等它们同时过期，比较固定 TTL 与抖动 TTL 的重回源数量。
     *
     * <p>L1 在这里被关闭，确保每次读都会真的落到 L2，否则本地缓存会掩盖过期问题。
     */
    public AvalancheResult avalanche(int keys, long baseTtlMillis, boolean jitter) {
        requireBetween("keys", keys, 1, 50);
        requireBetween("baseTtlMillis", baseTtlMillis, 50, 5_000);

        ProductRepository repository = new ProductRepository(0);
        MultiLevelProductCache cache = newCache(repository, false, 0, Duration.ofMillis(1), jitter ? 0.25 : 0);

        List<Long> ids = repository.existingIds().limit(keys).boxed().toList();
        Duration ttl = Duration.ofMillis(baseTtlMillis);

        long start = System.nanoTime();
        for (long id : ids) {
            cache.get(id, ttl, MultiLevelProductCache.NullPolicy.DISABLED);
        }
        long afterWarmup = repository.loadCount();

        sleep(baseTtlMillis + 10);

        for (long id : ids) {
            cache.get(id, ttl, MultiLevelProductCache.NullPolicy.DISABLED);
        }
        long reloads = repository.loadCount() - afterWarmup;
        return new AvalancheResult(jitter, keys, reloads, elapsed(start));
    }

    /**
     * 热点：90% 的请求打同一个 key，10% 打其余 49 个 key，比较有无 L1 时的命中结构。
     */
    public HotKeyResult hotKey(int requests, boolean l1Enabled) {
        requireBetween("requests", requests, 1, 200_000);

        ProductRepository repository = new ProductRepository(0);
        MultiLevelProductCache cache = newCache(repository, l1Enabled, 1024, LAB_TTL, 0);

        long start = System.nanoTime();
        for (int i = 0; i < requests; i++) {
            long id = i % 10 < 9 ? 1L : 2L + (i % 49);
            cache.get(id, LAB_TTL, MultiLevelProductCache.NullPolicy.DISABLED);
        }
        CacheCounters.Snapshot snapshot = cache.counters();
        return new HotKeyResult(l1Enabled,
                requests,
                snapshot.l1Hits(),
                snapshot.l2Hits(),
                snapshot.loads(),
                snapshot.l1HitRate(),
                elapsed(start));
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

    private static long elapsed(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发读实验超时");
            }
        } catch (InterruptedException e) {
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
