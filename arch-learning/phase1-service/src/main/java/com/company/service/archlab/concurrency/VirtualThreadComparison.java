package com.company.service.archlab.concurrency;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 平台线程池 vs 虚拟线程：同一个"阻塞型"负载，两组数字。
 *
 * <p>【解决什么问题】高并发场景下线程大部分时间在等 I/O。平台线程一个萝卜一个坑，
 * 池子开多大，同时在途的请求就最多有多少；开太大又会把内存和调度压垮。
 * 虚拟线程把"线程"和"内核线程"解耦，让"每个请求一个线程"重新变得可行。
 *
 * <p>【为什么这样设计】关键认知：
 * <ul>
 *   <li>平台线程 = 1:1 映射到 OS 线程，阻塞时那个内核线程就只能干等，
 *       所以吞吐被 poolSize 硬卡死；</li>
 *   <li>虚拟线程 = M:N，阻塞时自动从载体线程上卸载（unmount），
 *       少量载体线程就能承载海量并发任务；</li>
 *   <li>虚拟线程解决的是"阻塞等待太贵"，不是"CPU 计算太慢"：
 *       CPU 密集任务上它不会更快，甚至可能因调度开销略慢。</li>
 * </ul>
 * 所以真正的判断标准是：你的线程在等 I/O（数据库、RPC、Redis）还是在算东西。
 *
 * <p>【怎么读这段代码】两组的任务体 {@link #executeAll} 完全相同，只有 Executor 不同，
 * 这样"耗时差异"只能归因于调度模型。看数字：平台组耗时大约是 (tasks / poolSize) * ioMillis，
 * 虚拟线程组接近 ioMillis —— 因为 2000 个任务其实是在同一批载体线程上"轮流睡觉"。
 */
@Service
public class VirtualThreadComparison {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadComparison.class);

    /**
     * 单组实验结果。
     *
     * @param mode                实现方式标识：platform-pool 或 virtual-thread
     * @param tasks               任务总数
     * @param ioMillis            每个任务模拟的 I/O 等待时长
     * @param threads             实际能同时干活的线程数（平台组受池大小限制）
     * @param elapsedMillis       整组任务跑完的墙钟耗时
     * @param throughputPerSecond 每秒完成任务数 = tasks * 1000 / elapsedMillis
     */
    public record Result(String mode,
                         int tasks,
                         long ioMillis,
                         int threads,
                         long elapsedMillis,
                         double throughputPerSecond) {
    }

    /**
     * 两组结果对比。
     *
     * @param platform      平台线程池组
     * @param virtualThreads 虚拟线程组
     * @param speedup       平台组耗时 / 虚拟线程组耗时，越大说明虚拟线程优势越明显
     */
    public record Comparison(Result platform, Result virtualThreads, double speedup) {
    }

    /**
     * 单次对比：平台线程池（固定大小）vs 虚拟线程（每个任务一个虚拟线程）。
     *
     * @param tasks          任务总数，取值 [1, 100000]
     * @param ioMillis       每个任务阻塞的毫秒数，取值 [1, 5000]
     * @param platformPoolSize 平台线程池大小，取值 [1, 10000]
     * @return 两组结果与加速比
     * @throws IllegalArgumentException 参数越界时抛出
     * @throws IllegalStateException    任务在 120s 内没跑完，或主线程被中断
     */
    public Comparison compare(int tasks, long ioMillis, int platformPoolSize) {
        log.info("【线程模型对比】开始，任务数={}，单任务阻塞={}ms，平台线程池={}",
                tasks, ioMillis, platformPoolSize);
        // 先跑平台线程，再跑虚拟线程：顺序执行避免两组抢同一批 CPU 核，数字才可比
        Result platform = runPlatform(tasks, ioMillis, platformPoolSize);
        Result virtual = runVirtual(tasks, ioMillis);
        // 虚拟线程耗时理论上不会为 0（至少 1ms），但防御一下除零
        double speedup = platform.elapsedMillis() == 0
                ? 0
                : (double) platform.elapsedMillis() / virtual.elapsedMillis();
        double rounded = round(speedup);
        log.info("【线程模型对比】结束，平台组={}ms（{} 线程），虚拟线程组={}ms（{} 线程），加速比={}x",
                platform.elapsedMillis(), platform.threads(),
                virtual.elapsedMillis(), virtual.threads(), rounded);
        return new Comparison(platform, virtual, rounded);
    }

    /**
     * 用固定大小的平台线程池跑完全部任务。
     *
     * @param tasks    任务总数
     * @param ioMillis 每个任务阻塞毫秒数
     * @param poolSize 线程池大小，决定同时最多有几个任务在跑
     * @return 平台线程池组的结果
     * @throws IllegalArgumentException 参数越界时抛出
     */
    public Result runPlatform(int tasks, long ioMillis, int poolSize) {
        validate(tasks, ioMillis, poolSize);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable, "platform-io");
            // 守护线程：实验线程池即使没关干净，也不会阻止 JVM 退出
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

    /**
     * 用虚拟线程执行器跑完全部任务，每个任务一个虚拟线程。
     *
     * @param tasks    任务总数
     * @param ioMillis 每个任务阻塞毫秒数
     * @return 虚拟线程组的结果
     * @throws IllegalArgumentException 参数越界时抛出
     */
    public Result runVirtual(int tasks, long ioMillis) {
        // 这里 poolSize 传 1 只是为了让校验方法能复用，虚拟线程不受这个值限制
        validate(tasks, ioMillis, 1);
        // 每任务一个虚拟线程：不需要预先估算池大小，这正是它相对固定线程池的核心优势
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            long elapsed = executeAll(pool, tasks, ioMillis);
            return new Result("virtual-thread", tasks, ioMillis, tasks,
                    elapsed, throughput(tasks, elapsed));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 公共的任务提交与计时逻辑：两组实验共用，保证任务体完全一致。
     *
     * @param pool     任务执行器（平台池或虚拟线程池）
     * @param tasks    任务总数
     * @param ioMillis 每个任务阻塞毫秒数
     * @return 从统一放行到全部完成的墙钟耗时（毫秒，最小 1）
     * @throws IllegalStateException 任务 120s 未完成，或等待期间被中断
     */
    private long executeAll(ExecutorService pool, int tasks, long ioMillis) {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);
        for (int i = 0; i < tasks; i++) {
            pool.execute(() -> {
                try {
                    // 等统一放行，避免"先提交的先开始"把吞吐算高
                    start.await();
                    // 模拟一次 I/O 等待：虚拟线程在这里会卸载，平台线程在这里会占着内核线程空等
                    Thread.sleep(ioMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    // 放在 finally：即使任务被中断也要 countDown，否则主线程一直等到超时
                    done.countDown();
                }
            });
        }
        long begin = System.nanoTime();
        start.countDown();
        try {
            // 必须有超时：虚拟线程可以轻松创建十万个，一旦参数失控，无超时的等待会把请求线程钉死
            if (!done.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("任务未在 120s 内完成，请减小 tasks 或 ioMillis");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("虚拟线程对比被中断", e);
        }
        return Math.max(1L, (System.nanoTime() - begin) / 1_000_000L);
    }

    /**
     * 参数校验集中在一处，两个入口共用，避免漏校验导致实验把机器打挂。
     *
     * @throws IllegalArgumentException 任一参数越界时抛出
     */
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

    /**
     * 按任务总数折算每秒完成数；耗时已保证 >= 1ms，不会除零。
     */
    private double throughput(int tasks, long elapsedMillis) {
        return round(tasks * 1000.0 / Math.max(1L, elapsedMillis));
    }

    /**
     * 只保留两位小数，避免返回体里出现一长串浮点尾数。
     */
    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
