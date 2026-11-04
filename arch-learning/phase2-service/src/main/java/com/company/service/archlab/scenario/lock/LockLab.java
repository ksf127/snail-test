package com.company.service.archlab.scenario.lock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.company.starter.lock.DistributedLock;
import com.company.starter.lock.LockHandle;

/**
 * 分布式锁的最小可用验证：N 个线程各做 M 次"读-改-写"，比较加锁与不加锁的最终计数。
 *
 * <p>【解决什么问题】"读-改-写"不是原子操作：两个线程同时读到 10，各自加 1 写回 11，
 * 最终只增加了 1 而不是 2，这就是丢更新。单机可以用 {@code synchronized}，
 * 但服务部署多实例后，JVM 锁只能锁住自己进程内的线程，跨实例依然会互相覆盖。
 * 分布式锁的作用就是把"临界区"扩展到多个进程。
 *
 * <p>【为什么这样设计】不加锁那一组一定会丢更新，加锁那一组必须严格等于 N×M，
 * 这是正确性验证。但实验的重点不是"锁能跑通"，而是把代价摆在明面上：
 * 每一次自增都要两次 Redis 往返（加锁 + 解锁），吞吐会掉一个数量级——
 * 所以生产里能用原子操作（INCR / Lua）解决的，不要用分布式锁。
 * 两组共用同一段 {@code businessPause} 业务耗时，这样 {@code elapsedMillis} 的差值
 * 才是纯粹的锁开销；用 {@link CyclicBarrier} 让线程同时起跑，否则线程池会串行执行，形不成竞争。
 *
 * <p>【怎么读这段代码】先看内部类 {@link Counter}：一个普通 int，没有任何同步；
 * 再看 {@link #increment} 的"读 → 业务耗时 → 写回"，理解窗口在哪里；
 * 然后看 {@link #incrementUnderLock} 如何用 try-with-resources 保证异常时也释放锁；
 * 最后比较返回结果里的 {@code expected} 与 {@code actual}，以及两组的耗时差异。
 */
@Service
public class LockLab {

    private static final Logger log = LoggerFactory.getLogger(LockLab.class);

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

    /**
     * 并发自增对照实验。
     *
     * @param locked            true 走分布式锁，false 裸自增
     * @param threads           并发线程数，范围 [1, 100]
     * @param incrementsPerThread 每个线程自增次数，范围 [1, 1000]
     * @return 期望值（threads × incrementsPerThread）、实际值与耗时
     * @throws IllegalArgumentException 参数越界时抛出
     * @throws IllegalStateException    加锁超时或实验执行失败时抛出
     */
    public Result counter(boolean locked, int threads, int incrementsPerThread) {
        if (threads < 1 || threads > 100) {
            throw new IllegalArgumentException("threads 必须在 [1, 100] 之间");
        }
        if (incrementsPerThread < 1 || incrementsPerThread > 1_000) {
            throw new IllegalArgumentException("iterations 必须在 [1, 1000] 之间");
        }

        log.info("【分布式锁实验】开始，provider={}，locked={}，threads={}，每线程自增={} 次",
                distributedLock.provider(), locked, threads, incrementsPerThread);
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
                // 设超时是为了防止某个线程拿不到锁时把整个实验挂死。
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

        Result result = new Result(distributedLock.provider(),
                locked,
                threads,
                incrementsPerThread,
                (long) threads * incrementsPerThread,
                counter.value,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        log.info("【分布式锁实验】结束，locked={}，期望={}，实际={}，耗时={}ms",
                locked, result.expected(), result.actual(), result.elapsedMillis());
        if (result.actual() != result.expected()) {
            log.warn("【分布式锁实验】计数丢了 {} 次，说明读-改-写没有被互斥保护",
                    result.expected() - result.actual());
        }
        return result;
    }

    /**
     * 不加锁的读-改-写：读和写之间刻意留出业务耗时，让竞态窗口足够大、每次都复现。
     */
    private static void increment(Counter counter) {
        int read = counter.value;
        // 读和写之间留出真实的业务耗时，让丢更新稳定复现；
        // 这只是把"业务处理"摆进临界区，不是人为制造 bug
        businessPause();
        counter.value = read + 1;
    }

    /**
     * 加分布式锁的读-改-写：加锁与释放之间的整段代码，跨进程只有一个线程能进入。
     */
    private void incrementUnderLock(Counter counter) {
        // 多线程抢同一把锁，必须用带等待的 lock()，否则大部分线程都会"加锁失败"
        try (LockHandle handle = distributedLock.lock("counter",
                Duration.ofSeconds(5), Duration.ofSeconds(5))) {
            if (handle == null) {
                log.warn("【分布式锁实验】5s 内没有拿到锁 key=counter");
                throw new IllegalStateException("获取锁超时");
            }
            int read = counter.value;
            businessPause();
            counter.value = read + 1;
            // try-with-resources 会在离开代码块（包括抛异常）时调用 close() 释放锁，
            // 避免"业务异常导致锁永远不释放"这种最危险的死锁。
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

    /** 所有线程在栅栏处对齐，保证同一瞬间开始竞争。 */
    private static void awaitStartLine(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发起跑同步失败", e);
        }
    }

    /**
     * 被实验的计数器：故意用普通 int，没有任何同步，用来复现"丢更新"。
     * 加锁组依靠外部锁保证互斥，字段本身仍然不需要 volatile。
     */
    private static final class Counter {

        private int value;
    }
}
