package com.company.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 同一个「高并发计数」需求，三种实现的吞吐对照：
 * <ul>
 *   <li>synchronized：悲观锁，竞争时线程挂起/唤醒，上下文切换成本高</li>
 *   <li>AtomicLong：CAS 自旋，竞争越激烈失败重试越多</li>
 *   <li>LongAdder：分段累加（Cell 数组），把热点分散到多个缓存行</li>
 * </ul>
 * 运行：java -jar arch-learning/arch-learning-benchmark/target/arch-learning-benchmarks.jar CounterBenchmark -t 8
 * 结论预期：低竞争三者接近；8 线程以上 AtomicLong 明显落后，LongAdder 接近线性扩展。
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Group)
public class CounterBenchmark {

    private final AtomicLong atomic = new AtomicLong();
    private final LongAdder adder = new LongAdder();
    private long plain;

    @Benchmark
    @Group("atomic")
    @GroupThreads(8)
    public void atomicIncrement(Blackhole bh) {
        bh.consume(atomic.incrementAndGet());
    }

    @Benchmark
    @Group("adder")
    @GroupThreads(8)
    public void longAdderIncrement() {
        // increment() 写 volatile cell，有外部可见副作用，JIT 不会消除
        adder.increment();
    }

    @Benchmark
    @Group("synchronized")
    @GroupThreads(8)
    public void synchronizedIncrement(Blackhole bh) {
        long value;
        synchronized (this) {
            value = ++plain;
        }
        // 把消费放在临界区外，避免把 Blackhole 的开销算进锁持有时间
        bh.consume(value);
    }
}
