package com.company.service.archlab.concurrency;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

/**
 * 平台线程池 vs 虚拟线程：同一个"阻塞型"负载，两组数字。
 *
 * <p>关键认知：
 * <ul>
 *   <li>平台线程 = 1:1 映射到 OS 线程，阻塞时那个内核线程就只能干等，
 *       所以吞吐被 poolSize 硬卡死；</li>
 *   <li>虚拟线程 = M:N，阻塞时自动从载体线程上卸载（unmount），
 *       少量载体线程就能承载海量并发任务；</li>
 *   <li>虚拟线程解决的是"阻塞等待太贵"，不是"CPU 计算太慢"：
 *       CPU 密集任务上它不会更快，甚至可能因调度开销略慢。</li>
 * </ul>
 *
 * <p>所以真正的判断标准是：你的线程在等 I/O（数据库、RPC、Redis）还是在算东西。
 */
@Service
public class VirtualThreadComparison {

    public record Result(String mode,
                         int tasks,
                         long ioMillis,
                         int threads,
                         long elapsedMillis,
                         double throughputPerSecond) {
    }

    public record Comparison(Result platform, Result virtualThreads, double speedup) {
    }

    /**
     * 单次对比：平台线程池（固定大小）vs 虚拟线程（每个任务一个虚拟线程）。
     */
    public Comparison compare(int tasks, long ioMillis, int platformPoolSize) {
        Result platform = runPlatform(tasks, ioMillis, platformPoolSize);
        Result virtual = runVirtual(tasks, ioMillis);
        double speedup = platform.elapsedMillis() == 0
                ? 0
                : (double) platform.elapsedMillis() / virtual.elapsedMillis();
        return new Comparison(platform, virtual, round(speedup));
    }

    public Result runPlatform(int tasks, long ioMillis, int poolSize) {
        validate(tasks, ioMillis, poolSize);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable, "platform-io");
            thread.setDaemon(true);
            return thread;
        });
        try {
            long elapsed = executeAll(pool, tasks, ioMillis);
            return new Result("platform-pool", tasks, ioMillis, Math.min(tasks, poolSize),
                    elapsed, throughput(tasks, elapsed));
        } finally {
            pool.shutdownNow();
        }
    }

    public Result runVirtual(int tasks, long ioMillis) {
        validate(tasks, ioMillis, 1);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            long elapsed = executeAll(pool, tasks, ioMillis);
            return new Result("virtual-thread", tasks, ioMillis, tasks,
                    elapsed, throughput(tasks, elapsed));
        } finally {
            pool.shutdownNow();
        }
    }

    private long executeAll(ExecutorService pool, int tasks, long ioMillis) {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);
        for (int i = 0; i < tasks; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    // 模拟一次 I/O 等待：虚拟线程在这里会卸载，平台线程在这里会占着内核线程空等
                    Thread.sleep(ioMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        long begin = System.nanoTime();
        start.countDown();
        try {
            if (!done.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("任务未在 120s 内完成，请减小 tasks 或 ioMillis");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("虚拟线程对比被中断", e);
        }
        return Math.max(1L, (System.nanoTime() - begin) / 1_000_000L);
    }

    private void validate(int tasks, long ioMillis, int poolSize) {
        if (tasks <= 0 || tasks > 100_000) {
            throw new IllegalArgumentException("tasks 必须落在 [1, 100000]");
        }
        if (ioMillis <= 0 || ioMillis > 5_000) {
            throw new IllegalArgumentException("ioMillis 必须落在 [1, 5000]");
        }
        if (poolSize <= 0 || poolSize > 10_000) {
            throw new IllegalArgumentException("poolSize 必须落在 [1, 10000]");
        }
    }

    private double throughput(int tasks, long elapsedMillis) {
        return round(tasks * 1000.0 / Math.max(1L, elapsedMillis));
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
