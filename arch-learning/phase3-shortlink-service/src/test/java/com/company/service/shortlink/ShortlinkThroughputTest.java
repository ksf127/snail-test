package com.company.service.shortlink;

import com.company.service.shortlink.domain.Base62;
import com.company.service.shortlink.domain.Shortlink;
import com.company.service.shortlink.infra.CacheEntry;
import com.company.service.shortlink.infra.MemoryRemoteCache;
import com.company.service.shortlink.infra.ShortlinkCache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单机吞吐对照：同一份数据，走 L1（Caffeine）与只走 L2 的耗时对比。
 *
 * <p>注意：这里测的是"缓存层"的吞吐，不是整条 HTTP 链路的吞吐。
 * CI 机器噪声大，因此不设性能阈值，只断言"流量确实被 L1 挡住了"，
 * 耗时数字用于观察趋势（本机与线上都要自己跑一遍再定节点数）。
 */
class ShortlinkThroughputTest {

    private static final int KEY_COUNT = 1_000;

    private static final int THREADS = 8;

    private static final int OPS_PER_THREAD = 25_000;

    @Test
    void l1ShouldAbsorbAlmostAllTraffic() throws Exception {
        MemoryRemoteCache l2 = new MemoryRemoteCache();
        ShortlinkCache cache = new ShortlinkCache(l2, 100_000,
                Duration.ofMinutes(10), Duration.ofSeconds(30),
                Duration.ofMinutes(30), Duration.ofMinutes(2), 0.1);

        Instant now = Instant.now();
        List<String> codes = new ArrayList<>(KEY_COUNT);
        for (int i = 0; i < KEY_COUNT; i++) {
            String code = Base62.encodeFixed(i);
            codes.add(code);
            cache.put(code, CacheEntry.of(new Shortlink((long) i, code,
                    "https://example.com/target/" + i, now, null)));
        }

        long totalOps = (long) THREADS * OPS_PER_THREAD;
        long l1ElapsedNanos = runPool(totalOps, index -> cache.lookup(codes.get(index % KEY_COUNT)));
        ShortlinkCache.Stats stats = cache.stats();

        AtomicLong l2Hits = new AtomicLong();
        long l2ElapsedNanos = runPool(totalOps, index -> {
            if (l2.get(codes.get(index % KEY_COUNT)).isPresent()) {
                l2Hits.incrementAndGet();
            }
        });

        report("L1+L2 (Caffeine)", totalOps, l1ElapsedNanos);
        report("L2 only (Map/Redis 替身)", totalOps, l2ElapsedNanos);
        System.out.printf("L1 命中=%d, L2 命中=%d, 未命中=%d%n",
                stats.l1Hits(), stats.l2Hits(), stats.misses());

        assertThat(stats.l1Hits()).isEqualTo(totalOps);
        assertThat(stats.l2Hits()).isZero();
        assertThat(stats.misses()).isZero();
        assertThat(l2Hits.get()).isEqualTo(totalOps);
    }

    private static void report(String label, long ops, long elapsedNanos) {
        double qps = ops / (elapsedNanos / 1_000_000_000d);
        double microsPerOp = elapsedNanos / 1_000d / ops;
        System.out.printf("%-28s %d ops in %d ms → %.0f ops/s, %.3f µs/op%n",
                label, ops, elapsedNanos / 1_000_000, qps, microsPerOp);
    }

    private static long runPool(long totalOps, Op op) throws Exception {
        long opsPerThread = totalOps / THREADS;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int t = 0; t < THREADS; t++) {
                int offset = t;
                pool.execute(() -> {
                    try {
                        start.await();
                        int base = offset * (int) opsPerThread;
                        for (int i = 0; i < opsPerThread; i++) {
                            op.apply(base + i);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            long begin = System.nanoTime();
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
            return System.nanoTime() - begin;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Op {
        void apply(int index);
    }
}
