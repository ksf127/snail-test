package com.company.service.archlab.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Service;

/**
 * 同一个计数器，五种写法，跑出五个数字。
 *
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
 */
@Service
public class CounterComparison {

    public enum Strategy {
        /** 裸 long，故意不加任何同步，用来展示丢失更新。 */
        PLAIN,
        /** 对象监视器。 */
        SYNCHRONIZED,
        /** CAS 无锁计数。 */
        ATOMIC_LONG,
        /** 分段累加，高并发首选。 */
        LONG_ADDER,
        /** 自研自旋锁。 */
        SPIN_LOCK
    }

    public record Result(Strategy strategy,
                         int threads,
                         long incrementsPerThread,
                         long expected,
                         long actual,
                         boolean correct,
                         long elapsedMillis,
                         double opsPerSecond) {
    }

    public Result run(Strategy strategy, int threads, long incrementsPerThread) {
        if (threads <= 0 || threads > 256) {
            throw new IllegalArgumentException("threads 必须落在 [1, 256]");
        }
        if (incrementsPerThread <= 0 || incrementsPerThread > 20_000_000L) {
            throw new IllegalArgumentException("incrementsPerThread 必须落在 [1, 20000000]");
        }
        Counter counter = newCounter(strategy);
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
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (long j = 0; j < incrementsPerThread; j++) {
                        counter.increment();
                    }
                    done.countDown();
                });
            }
            ready.await();
            long begin = System.nanoTime();
            start.countDown();
            done.await();
            long elapsedNanos = System.nanoTime() - begin;

            long expected = threads * incrementsPerThread;
            long actual = counter.value();
            long elapsedMillis = Math.max(1L, elapsedNanos / 1_000_000L);
            double opsPerSecond = expected * 1000.0 / elapsedMillis;
            return new Result(strategy, threads, incrementsPerThread,
                    expected, actual, actual == expected, elapsedMillis, opsPerSecond);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("计数器对比被中断", e);
        } finally {
            pool.shutdownNow();
        }
    }

    public List<Result> runAll(int threads, long incrementsPerThread) {
        List<Result> results = new ArrayList<>();
        for (Strategy strategy : Strategy.values()) {
            results.add(run(strategy, threads, incrementsPerThread));
        }
        return results;
    }

    private Counter newCounter(Strategy strategy) {
        return switch (strategy) {
            case PLAIN -> new PlainCounter();
            case SYNCHRONIZED -> new SynchronizedCounter();
            case ATOMIC_LONG -> new AtomicCounter();
            case LONG_ADDER -> new LongAdderCounter();
            case SPIN_LOCK -> new SpinLockCounter();
        };
    }

    private interface Counter {
        void increment();

        long value();
    }

    private static final class PlainCounter implements Counter {

        private long value;

        @Override
        public void increment() {
            value++;
        }

        @Override
        public long value() {
            return value;
        }
    }

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

    private static final class LongAdderCounter implements Counter {

        private final LongAdder value = new LongAdder();

        @Override
        public void increment() {
            value.increment();
        }

        @Override
        public long value() {
            return value.sum();
        }
    }

    private static final class SpinLockCounter implements Counter {

        private final CasSpinLock lock = new CasSpinLock();

        private long value;

        @Override
        public void increment() {
            lock.lock();
            try {
                value++;
            } finally {
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
