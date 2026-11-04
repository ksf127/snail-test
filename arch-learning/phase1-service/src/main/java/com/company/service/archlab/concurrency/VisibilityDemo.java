package com.company.service.archlab.concurrency;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 【实验目的】验证 volatile 的可见性语义：一个线程写、另一个线程读，读线程多久能看到新值。
 *
 * <p>【解决什么问题】多核 CPU 下每个核心都有自己的缓存（L1/L2），线程 A 改了变量，
 * 线程 B 可能一直读自己缓存里的旧值。更糟的是 JIT 发现循环体里没人改这个变量，
 * 会把读操作提升成"寄存器读取"，于是循环永远跳不出去。
 *
 * <p>【两种实现只差一个关键字】{@link PlainFlag} 和 {@link VolatileFlag} 的代码几乎一模一样，
 * 唯一区别是字段上有 {@code volatile}。这样对照实验的变量是唯一的。
 *
 * <p>【预期现象】
 * <ul>
 *   <li>useVolatile=true：写线程 set 之后，读线程很快退出循环，polls 很小；</li>
 *   <li>useVolatile=false：可能超时（observed=false），也可能"碰巧"成功 ——
 *       因为 x86 的 MESI 缓存一致性协议比 JMM 的要求更严格，
 *       这正是"本地跑不出来、线上一改就炸"的经典来源。</li>
 * </ul>
 * <p>结论：{@code volatile} 保证可见性与有序性，<b>不保证原子性</b>，所以它救不了 {@code count++}。
 *
 * <p>【实验为什么要带超时】读线程最多自旋 timeoutMillis，不会把 Tomcat 的请求线程挂死。
 *
 * <p>【为什么本地跑出来两边数字一样】（这是本实验最重要的一课）
 * <ul>
 *   <li>日志里的「距开始约 64ms」并不是可见性延迟：写线程本身先睡了 50ms，
 *       真实的"写入 → 读到"只占其中几微秒，被 50ms 的固定睡眠完全淹没了。
 *       所以本类额外统计 {@code latencyMicros}，只从"写线程置位的瞬间"开始计时；</li>
 *   <li>x86-64 的 TSO 内存模型比 JMM 要求严格得多：普通写也会很快传播到其他核心的缓存，
 *       普通读几微秒内就能看到新值。所以两种实现在 x86 上几乎测不出差别；</li>
 *   <li>真正会出事的是 JIT：如果循环足够"干净"（直接读字段、循环体空），
 *       C2 会把读操作提升到循环外，变成"读寄存器"，于是死循环。
 *       本实验的循环体里有 {@code System.nanoTime()} 和接口调用 {@code flag.get()}，
 *       C2 没法证明它是循环不变量，所以读没被提升——这恰恰说明：
 *       <b>可见性 Bug 是否出现取决于编译器优化，而不是取决于你用没用 volatile。</b></li>
 * </ul>
 * 结论：{@code useVolatile=false} 在这台机器上"跑得通"，不能证明代码是对的；
 * 线上换 CPU 架构（ARM）、换 JDK、或者只是改了一行循环体，问题就可能突然出现。
 */
@Service
public class VisibilityDemo {

    private static final Logger log = LoggerFactory.getLogger(VisibilityDemo.class);

    /**
     * 写线程启动后先睡 50ms 再生产数据。
     * 这 50ms 是给读线程"先进循环"用的：如果写线程一上来就 set，
     * 读线程可能还没开始读就已经是新值了，实验跑不出差异。
     */
    private static final long WRITER_DELAY_MILLIS = 50L;

    /**
     * 跑一次对照实验。
     *
     * @param useVolatile    true 用 volatile 字段，false 用普通字段
     * @param timeoutMillis  读线程最多自旋多久，超出就判定"没看到"
     */
    public Result run(boolean useVolatile, long timeoutMillis) {
        if (timeoutMillis <= 0 || timeoutMillis > 10_000) {
            throw new IllegalArgumentException("timeoutMillis 必须落在 [1, 10000]");
        }

        // 唯一的变量就在这里：选哪种实现。其余代码完全共用。
        SharedFlag flag = useVolatile ? new VolatileFlag() : new PlainFlag();
        log.info("【可见性实验】开始，useVolatile={}，实现类={}，超时={}ms",
                useVolatile, flag.getClass().getSimpleName(), timeoutMillis);

        // 写线程"真正置位"的时刻。0 表示还没置位。
        // 用 AtomicLong 而不是普通 long：volatile 写自带 StoreStore 语义，
        // 保证"先写时间戳、后置位"的顺序不会被编译器/CPU 颠倒，测出来的延迟才有意义。
        AtomicLong setAtNanos = new AtomicLong(0L);

        Thread writer = new Thread(() -> {
            try {
                // 先睡 50ms，确保读线程已经进入自旋循环
                TimeUnit.MILLISECONDS.sleep(WRITER_DELAY_MILLIS);
            } catch (InterruptedException e) {
                // 被中断要恢复中断标记，否则上层感知不到中断信号
                Thread.currentThread().interrupt();
                return;
            }
            // 日志必须打在取时间戳之前：日志落盘本身要几十上百微秒，夹在"取时间 → 置位"中间
            // 会把测出来的延迟从几微秒放大到几百微秒，让数字彻底失真
            log.info("【可见性实验】写线程睡醒，准备把 flag 置为 true");
            // 时间戳紧挨着 set：这样"写入到可见"的计时起点才是置位那一刻，
            // 而不是线程启动那一刻（否则 50ms 的睡眠会把真实延迟盖住）
            setAtNanos.set(System.nanoTime());
            flag.set();
        }, "visibility-writer");
        // 守护线程：主流程异常退出时不会把 JVM 拖住
        writer.setDaemon(true);
        writer.start();

        long begin = System.nanoTime();
        long deadline = begin + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long polls = 0L;
        while (!flag.get()) {
            polls++;
            // 注意：这个循环里绝对不能打日志。
            // 任何 IO 都会带来内存屏障级别的副作用，把"看不到新值"变成"看到了"，实验直接失效。
            if (System.nanoTime() >= deadline) {
                log.warn("【可见性实验】{}ms 内没有看到新值（useVolatile={}，自旋 {} 次，写线程{}置位）。"
                                + "这说明读线程一直在读自己的本地副本，真实业务里就是死循环",
                        timeoutMillis, useVolatile, polls,
                        setAtNanos.get() == 0L ? "尚未" : "已经");
                return new Result(useVolatile, false, timeoutMillis, polls, -1L);
            }
            // JDK9+ 的空转提示：告诉 CPU 我在自旋，可以让出执行资源、降低功耗
            Thread.onSpinWait();
        }
        // 出循环的瞬间立刻取时间：必须在读 setAtNanos 之前取，
        // 因为读 AtomicLong 是一次 volatile 读，会引入额外延迟，把测量结果放大
        long observedAt = System.nanoTime();
        long setAt = setAtNanos.get();
        // 微秒级取整，最小 0；单位选 µs 是因为真实延迟通常只有几微秒，用 ms 会全是 0
        long latencyMicros = setAt == 0L ? -1L : Math.max(0L, (observedAt - setAt) / 1_000L);
        long elapsedMillis = Math.max(1L, (observedAt - begin) / 1_000_000L);
        log.info("【可见性实验】读线程在第 {} 次自旋看到新值；写线程置位后约 {}µs 可见"
                        + "（距读线程启动共 {}ms，其中 {}ms 是写线程故意睡眠）",
                polls, latencyMicros, elapsedMillis, WRITER_DELAY_MILLIS);
        return new Result(useVolatile, true, elapsedMillis, polls, latencyMicros);
    }

    /**
     * 把"读/写一个标记位"抽象出来，是为了让两种实现可以无差别替换。
     * 没有这个接口，就没法做"只改一个关键字"的对照实验。
     */
    private interface SharedFlag {

        void set();

        boolean get();
    }

    /**
     * 实验结果。
     *
     * @param useVolatile   本次是否用了 volatile
     * @param observed      读线程在超时前是否看到了新值
     * @param elapsedMillis 从读线程启动到退出循环的耗时（含写线程固定 50ms 睡眠，不代表可见性延迟）
     * @param polls         读线程自旋了多少次，可粗略反映"卡了多久"
     * @param latencyMicros 写线程置位 → 读线程读到 的延迟（微秒，这才是可见性延迟）；
     *                      超时未看到时为 -1
     */
    public record Result(boolean useVolatile,
                         boolean observed,
                         long elapsedMillis,
                         long polls,
                         long latencyMicros) {
    }

    /**
     * 对照组：普通字段，没有任何同步。
     *
     * <p>两个线程各自持有这个对象的引用，但 value 这个字段可能被缓存在各自的 CPU 缓存里。
     * 写线程改了，读线程不一定看得到 —— 这就是"可见性问题"。
     */
    private static final class PlainFlag implements SharedFlag {

        private boolean value;

        @Override
        public void set() {
            value = true;
        }

        @Override
        public boolean get() {
            return value;
        }
    }

    /**
     * 实验组：volatile 字段。
     *
     * <p>写操作会在前面插入 StoreLoad 屏障，强制把新值刷回主内存；
     * 读操作会强制从主内存重新加载，跳过本地缓存。
     * 同时它禁止指令重排，所以也顺带保证了有序性。
     */
    private static final class VolatileFlag implements SharedFlag {

        private volatile boolean value;

        @Override
        public void set() {
            value = true;
        }

        @Override
        public boolean get() {
            return value;
        }
    }
}
