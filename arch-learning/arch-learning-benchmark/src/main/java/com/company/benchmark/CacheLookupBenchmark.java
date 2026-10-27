package com.company.benchmark;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * L1 本地缓存选型对照：Caffeine getIfPresent vs ConcurrentHashMap get。
 *
 * <p>访问模式按 90% 热点 / 10% 长尾构造（与真实商品详情流量一致），
 * 两边用同一份 key 序列，避免「随机命中率不同」导致结论失真。
 *
 * <p>运行：java -jar arch-learning/arch-learning-benchmark/target/arch-learning-benchmarks.jar CacheLookupBenchmark -t 8
 *
 * <p>实测（8 线程、90% 热点）：ConcurrentHashMap 约 2.8 亿 ops/s，Caffeine 约 6000 万 ops/s。
 * Caffeine 的 getIfPresent 不只是查表，还带读缓冲、淘汰与过期维护，所以纯命中吞吐更低；
 * 它的价值在 load-on-miss 合并、容量淘汰、TTL 与刷新，而不是单次 get 的绝对性能。选型不能只看这张表。
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(8)
public class CacheLookupBenchmark {

    private static final int KEY_SPACE = 10_000;
    private static final int HOT_KEYS = 100;
    /** 取 2 的幂，游标回绕可以用位运算代替取模 */
    private static final int PATTERN_LENGTH = 8_192;
    private static final int PATTERN_MASK = PATTERN_LENGTH - 1;

    @State(Scope.Benchmark)
    public static class Caches {

        final Integer[] keys = new Integer[KEY_SPACE];
        final int[] pattern = new int[PATTERN_LENGTH];
        final ConcurrentHashMap<Integer, String> map = new ConcurrentHashMap<>();
        final Cache<Integer, String> caffeine = Caffeine.newBuilder()
                .maximumSize(KEY_SPACE)
                .build();

        @Setup(Level.Trial)
        public void setUp() {
            for (int i = 0; i < KEY_SPACE; i++) {
                keys[i] = i;
                String value = "product-" + i;
                map.put(keys[i], value);
                caffeine.put(keys[i], value);
            }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < PATTERN_LENGTH; i++) {
                pattern[i] = random.nextInt(10) < 9
                        ? random.nextInt(HOT_KEYS)
                        : random.nextInt(KEY_SPACE);
            }
        }
    }

    /** 每个线程独立游标，避免共享计数器把测量对象变成 CAS */
    @State(Scope.Thread)
    public static class Cursor {
        int index;

        int next() {
            int current = index;
            index = (current + 1) & PATTERN_MASK;
            return current;
        }
    }

    @Benchmark
    public String caffeineGet(Caches caches, Cursor cursor) {
        return caches.caffeine.getIfPresent(caches.keys[caches.pattern[cursor.next()]]);
    }

    @Benchmark
    public String hashMapGet(Caches caches, Cursor cursor) {
        return caches.map.get(caches.keys[caches.pattern[cursor.next()]]);
    }
}
