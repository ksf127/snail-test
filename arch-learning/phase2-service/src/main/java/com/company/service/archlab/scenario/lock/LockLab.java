package com.company.service.archlab.scenario.lock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.company.starter.lock.DistributedLock;
import com.company.starter.lock.LockHandle;

import org.springframework.stereotype.Service;

/**
 * 分布式锁的最小可用验证：N 个线程各做 M 次"读-改-写"，比较加锁与不加锁的最终计数。
 *
 * <p>不加锁那一组一定会丢更新（自增不是原子操作），加锁那一组必须严格等于 N×M。
 * 这个实验的重点不是"锁能跑通"，而是把代价摆在明面上：
 * 每一次自增都要两次 Redis 往返（加锁 + 解锁），吞吐会掉一个数量级——
 * 所以生产里能用原子操作（INCR / Lua）解决的，不要用分布式锁。
 */
@Service
public class LockLab {

    public record Result(String provider,
                         boolean locked,
                         int threads,
                         int incrementsPerThread,
                         long expected,
                         long actual,
                         long elapsedMillis) {
    }

    private final DistributedLock distributedLock;

    public LockLab(DistributedLock distributedLock) {
        this.distributedLock = distributedLock;
    }

    public Result counter(boolean locked, int threads, int incrementsPerThread) {
        if (threads < 1 || threads > 100) {
            throw new IllegalArgumentException("threads 必须在 [1, 100] 之间");
        }
        if (incrementsPerThread < 1 || incrementsPerThread > 1_000) {
            throw new IllegalArgumentException("iterations 必须在 [1, 1000] 之间");
        }

        Counter counter = new Counter();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>(threads);
        // 让所有线程同时起跑：否则线程池会串行跑完一个再跑下一个，根本形不成竞争
        CyclicBarrier startLine = new CyclicBarrier(threads);
        long start = System.nanoTime();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    awaitStartLine(startLine);
                    for (int iteration = 0; iteration < incrementsPerThread; iteration++) {
                        if (locked) {
                            incrementUnderLock(counter);
                        } else {
                            increment(counter);
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("锁实验被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("锁实验执行失败", e);
        } finally {
            pool.shutdownNow();
        }

        return new Result(distributedLock.provider(),
                locked,
                threads,
                incrementsPerThread,
                (long) threads * incrementsPerThread,
                counter.value,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    private static void increment(Counter counter) {
        int read = counter.value;
        // 读和写之间留出真实的业务耗时，让丢更新稳定复现；
        // 这只是把"业务处理"摆进临界区，不是人为制造 bug
        businessPause();
        counter.value = read + 1;
    }

    private void incrementUnderLock(Counter counter) {
        // 多线程抢同一把锁，必须用带等待的 lock()，否则大部分线程都会"加锁失败"
        try (LockHandle handle = distributedLock.lock("counter",
                Duration.ofSeconds(5), Duration.ofSeconds(5))) {
            if (handle == null) {
                throw new IllegalStateException("获取锁超时");
            }
            int read = counter.value;
            businessPause();
            counter.value = read + 1;
        }
    }

    /**
     * 模拟 1 微秒的业务处理：加锁与不加锁两组都走同一段耗时，
     * 这样 elapsedMillis 的差值才是纯粹的锁开销。
     */
    private static void businessPause() {
        long deadline = System.nanoTime() + 1_000L;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static void awaitStartLine(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发起跑同步失败", e);
        }
    }

    private static final class Counter {

        private int value;
    }
}
