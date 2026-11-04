package com.company.service.archlab.concurrency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 同一个计数器，五种写法，跑出五个数字。
 *
 * <p>【解决什么问题】"多线程计数"是并发里最小的一个真实问题：
 * {@code count++} 在字节码层面是"读-改-写"三步，多线程下必然丢更新。
 * 这个类把五种修法的正确性与吞吐放在同一把尺子上量，回答"到底该选哪个"。
 *
 * <p>【为什么这样设计】如果把五种实现分散在不同类里，就得手动控制变量（线程数、增量、JVM 状态），
 * 结果不可比。所以这里用 {@link Counter} 接口统一抽象，再用 {@link Strategy} 枚举枚举出全部实现，
 * 一次运行只改一个变量。
 * <p>结论先行（这也是面试里最容易露馅的地方）：
 * <ul>
 *   <li>PLAIN：最快，但会丢更新，只配当"错误对照组"；</li>
 *   <li>SYNCHRONIZED：正确，竞争激烈时线程阻塞/唤醒的开销最大；</li>
 *   <li>ATOMIC_LONG：CAS 无锁，中等竞争下通常优于锁；</li>
 *   <li>LONG_ADDER：分段累加，高竞争下吞吐最好，代价是 sum() 不是实时精确值；</li>
 *   <li>SPIN_LOCK：临界区极短时可能更快，临界区一长就把 CPU 烧光。</li>
 * </ul>
 *
 * <p>这里的结果只做"顺序参考"，绝对数字请以 arch-learning-benchmark 的 JMH 结果为准：
 * 没有预热、没有消除死代码、没有控制 GC，直接看 microbenchmark 数字容易被 JIT 骗。
 *
 * <p>【怎么读这段代码】先看 {@link #run(Strategy, int, long)} 里的三个 {@link CountDownLatch}：
 * ready（线程都到齐）→ start（同一瞬间起跑）→ done（全部跑完）。
 * 起跑线对计时公平性是必须的，否则先创建的线程会多跑一截。
 * 再看五个内部 Counter 实现，它们之间的差异就是"同步手段"本身。
 */
@Service
public class CounterComparison {

    private static final Logger log = LoggerFactory.getLogger(CounterComparison.class);

    public enum Strategy {
        /**
         * 裸 long，故意不加任何同步，用来展示丢失更新。
         */
        PLAIN,
        /**
         * 对象监视器。
         */
        SYNCHRONIZED,
        /**
         * CAS 无锁计数。
         */
        ATOMIC_LONG,
        /**
         * 分段累加，高并发首选。
         */
        LONG_ADDER,
        /**
         * 自研自旋锁。
         */
        SPIN_LOCK
    }

    /**
     * 一次压测的结果。
     *
     * @param strategy            本次使用的同步策略
     * @param threads             并发线程数
     * @param incrementsPerThread 每个线程自增的次数
     * @param expected            期望总数 = threads * incrementsPerThread
     * @param actual              计数器最终读到的值
     * @param correct             实际值是否等于期望值，PLAIN 通常为 false
     * @param elapsedMillis       从统一放行到全部完成的耗时（毫秒，最小 1）
     * @param opsPerSecond        按期望总数折算的每秒操作数，用于横向比较吞吐
     */
    public record Result(Strategy strategy,
                         int threads,
                         long incrementsPerThread,
                         long expected,
                         long actual,
                         boolean correct,
                         long elapsedMillis,
                         double opsPerSecond) {
    }

    /**
     * 跑一次指定策略的计数实验。
     *
     * @param strategy           使用哪种同步策略
     * @param threads            并发线程数，取值 [1, 256]
     * @param incrementsPerThread 每个线程的自增次数，取值 [1, 20000000]
     * @return 本次实验的耗时、正确性、吞吐
     * @throws IllegalArgumentException 参数越界时抛出（由 Controller 翻译成 40001）
     * @throws IllegalStateException    主线程在等待完成时被中断，实验无法给出可信结果
     */
    public Result run(Strategy strategy, int threads, long incrementsPerThread) {
        if (threads <= 0 || threads > 256) {
            throw new IllegalArgumentException("threads 必须落在 [1, 256]");
        }
        if (incrementsPerThread <= 0 || incrementsPerThread > 20_000_000L) {
            throw new IllegalArgumentException("incrementsPerThread 必须落在 [1, 20000000]");
        }
        Counter counter = newCounter(strategy);
        // 用守护线程：实验跑飞时不会阻止 JVM 退出，也不会拖住应用停机
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "counter-" + strategy.name().toLowerCase());
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        // 所有线程都卡在这里，等主线程放行，保证同时起跑；
                        // 否则先提交的线程会先跑完，测出来的是"排队时间"而不是"并发成本"
                        start.await();
                    } catch (InterruptedException e) {
                        // 被中断要恢复中断标记，让上层能感知并终止后续流程
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // 高频循环体内禁止打日志：这里是整个实验的计时区间，
                    // 任何日志 IO 都会把数字污染成"日志性能测试"
                    for (long j = 0; j < incrementsPerThread; j++) {
                        counter.increment();
                    }
                    done.countDown();
                });
            }
            ready.await();
            log.info("【计数器对比】开始，策略={}，线程数={}，每线程自增={}次，期望总数={}",
                    strategy, threads, incrementsPerThread, threads * incrementsPerThread);
            long begin = System.nanoTime();
            start.countDown();
            done.await();
            long elapsedNanos = System.nanoTime() - begin;

            long expected = threads * incrementsPerThread;
            long actual = counter.value();
            // 至少记 1ms，避免整除得到 0 后算出无穷大的吞吐
            long elapsedMillis = Math.max(1L, elapsedNanos / 1_000_000L);
            double opsPerSecond = expected * 1000.0 / elapsedMillis;
            Result result = new Result(strategy, threads, incrementsPerThread,
                    expected, actual, actual == expected, elapsedMillis, opsPerSecond);
            if (result.correct()) {
                log.info("【计数器对比】完成，策略={}，实际值={}，耗时={}ms，吞吐≈{} ops/s",
                        strategy, actual, elapsedMillis, Math.round(opsPerSecond));
            } else {
                // 丢更新是预期内的结论（PLAIN），用 warn 让它在一堆 info 里跳出来
                log.warn("【计数器对比】结果不一致，策略={}，期望={}，实际={}，丢失={} 次更新。"
                                + "原因是没有互斥或原子性保证",
                        strategy, expected, actual, expected - actual);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("计数器对比被中断", e);
        } finally {
            // 无论成功失败都立即释放线程池，防止实验接口把容器线程耗光
            pool.shutdownNow();
        }
    }

    /**
     * 五种策略依次跑一遍，用同一组参数保证结果可比。
     *
     * @param threads             并发线程数
     * @param incrementsPerThread 每个线程自增次数
     * @return 按 {@link Strategy} 声明顺序排列的五条结果
     */
    public List<Result> runAll(int threads, long incrementsPerThread) {
        List<Result> results = new ArrayList<>();
        log.info("【计数器对比】测什么：同一个 count++ 问题，五种同步手段的正确性与吞吐；"
                + "谁和谁对照：PLAIN（对照组，必然丢更新）vs 其余四种（正确性应全为 true）；"
                + "参数：线程数={}，每线程自增={}次", threads, incrementsPerThread);
        // 串行跑而不是并发跑：同时跑五个实验会互相抢 CPU，数字就没法比较了
        for (Strategy strategy : Strategy.values()) {
            results.add(run(strategy, threads, incrementsPerThread));
        }
        // 汇总一次按耗时排序：单看每条日志无法回答"到底该选哪个"，
        // 只有把五个数字排在一行里，优劣才是可比的
        List<Result> ranked = new ArrayList<>(results);
        ranked.sort(Comparator.comparingLong(Result::elapsedMillis));
        log.info("【计数器对比】判读：正确性 {}，耗时从快到慢 {}",
                results.stream().allMatch(Result::correct) ? "全部正确" : "存在丢更新（符合 PLAIN 预期）",
                ranked.stream()
                        .map(r -> r.strategy() + "=" + r.elapsedMillis() + "ms")
                        .toList());
        return results;
    }

    /**
     * 按枚举选择具体实现，这里是唯一出现分支的地方——分支只负责"选实现"，不掺业务。
     */
    private Counter newCounter(Strategy strategy) {
        return switch (strategy) {
            case PLAIN -> new PlainCounter();
            case SYNCHRONIZED -> new SynchronizedCounter();
            case ATOMIC_LONG -> new AtomicCounter();
            case LONG_ADDER -> new LongAdderCounter();
            case SPIN_LOCK -> new SpinLockCounter();
        };
    }

    /**
     * 把"自增 + 读值"抽象出来，五种实现才能用同一段压测代码（只改一个变量）。
     */
    private interface Counter {
        void increment();

        long value();
    }

    /**
     * 错误对照组：裸 long，没有任何同步。
     * 多线程各自读到旧值再写回，更新会被覆盖。
     */
    private static final class PlainCounter implements Counter {

        private long value;

        @Override
        public void increment() {
            // 故意保留这个非原子操作：它就是"丢更新"的源头，不能改
            value++;
        }

        @Override
        public long value() {
            return value;
        }
    }

    /**
     * 对象监视器方案：正确但竞争时线程会阻塞/唤醒，切换成本高。
     * value() 也加锁，是为了读到其他线程写入的最新值（监视器释放时刷主存）。
     */
    private static final class SynchronizedCounter implements Counter {

        private long value;

        @Override
        public synchronized void increment() {
            value++;
        }

        @Override
        public synchronized long value() {
            return value;
        }
    }

    /**
     * CAS 无锁方案：失败就重试，不阻塞线程，中等竞争下通常比锁快。
     */
    private static final class AtomicCounter implements Counter {

        private final AtomicLong value = new AtomicLong();

        @Override
        public void increment() {
            value.incrementAndGet();
        }

        @Override
        public long value() {
            return value.get();
        }
    }

    /**
     * 分段累加方案：不同线程更新不同的 Cell，最后求和。
     * 高竞争下吞吐最高，代价是 sum() 期间并发写入可能统计不到（弱一致）。
     */
    private static final class LongAdderCounter implements Counter {

        private final LongAdder value = new LongAdder();

        @Override
        public void increment() {
            value.increment();
        }

        @Override
        public long value() {
            // 实验结束时所有写线程 already done，此时 sum() 才是精确的
            return value.sum();
        }
    }

    /**
     * 自研自旋锁方案：用来看"自旋 vs 阻塞"在极短临界区下的差异。
     * 注意锁对象是每实例独立的，避免了五个实验互相干扰。
     */
    private static final class SpinLockCounter implements Counter {

        private final CasSpinLock lock = new CasSpinLock();

        private long value;

        @Override
        public void increment() {
            lock.lock();
            try {
                value++;
            } finally {
                // 临界区只有一行，所以自旋等待可能比上下文切换更划算
                lock.unlock();
            }
        }

        @Override
        public long value() {
            lock.lock();
            try {
                return value;
            } finally {
                lock.unlock();
            }
        }
    }
}
