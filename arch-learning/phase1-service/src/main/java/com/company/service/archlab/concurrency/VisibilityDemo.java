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

    // ------------------------------------------------------------------
    // 上面那个 run() 在 x86 上测不出差异，因为循环体不够"干净"。
    // 下面这个才是能真正看到差别的实验：把循环体压到只剩一次字段读，
    // 让 C2 有机会把这次读提出循环 —— 那时普通字段就再也看不到新值了。
    // ------------------------------------------------------------------

    /**
     * 自旋上限，防止实验把 CPU 烧穿。
     *
     * <p>2e9 次自增大约 0.8 秒 CPU 时间。之所以要这么大，是因为必须让循环"热"到
     * C2（服务端编译器）完成编译并做出优化，否则测的只是解释执行的结果。
     */
    private static final long HOIST_SPIN_LIMIT = 2_000_000_000L;

    /**
     * 等待读线程退出的宽限时间。
     */
    private static final long HOIST_JOIN_MILLIS = 5_000L;

    /**
     * 【核心实验】把"读标记位"放进一个足够干净的循环，看 JIT 会不会把读提升出循环。
     *
     * <p>【为什么要另做一个实验】{@link #run(boolean, long)} 的循环体里有接口调用
     * （{@code flag.get()} 是多态分派）和 {@code System.nanoTime()}，C2 无法证明
     * "字段在循环期间不变"，所以不敢把读提到循环外，两种实现的表现就完全一样，
     * 只能测到调度噪声。
     *
     * <p>【这个实验的循环长什么样】
     * <pre>
     *   long n = 0;
     *   while (!holder.stop &amp;&amp; n &lt; LIMIT) { n++; }
     * </pre>
     * 循环体只有一次自增，条件里只有一次字段读 —— 这是 C2 最容易做"读提升"（loop-invariant
     * code motion）的形态：既然循环里没人写这个字段，就把读挪到循环外只做一次，
     * 循环于是退化成 {@code while (n < LIMIT)}。
     *
     * <p>【预期结果，两种实现天差地别】
     * <ul>
     *   <li>{@code useVolatile=true}：volatile 读不允许被提升（JMM 要求每次都重新读），
     *       所以主线程一置位，读线程很快就退出，{@code spins} 远小于上限；</li>
     *   <li>{@code useVolatile=false}：读被提出循环，主线程置位对读线程等于不存在，
     *       它会一路自旋到上限才退出，{@code sawWrite=false}。</li>
     * </ul>
     *
     * <p>【为什么循环要有 {@code n < LIMIT}】没有上限的话，普通字段那次会永远转下去，
     * Tomcat 的请求线程就再也回不来了。加上上限后，"没看到写入"表现为
     * "跑满上限"，同样能判定，但不会把服务拖死。
     *
     * @param useVolatile  true 用 volatile 字段，false 用普通字段
     * @param settleMillis 启动读线程后先等多久再置位，留给 JIT 编译；取值 [1, 5000]
     * @return 自旋次数、是否看到了写入、耗时
     * @throws IllegalArgumentException settleMillis 越界
     * @throws IllegalStateException    等待过程中被中断
     */
    public HoistResult runHoisting(boolean useVolatile, long settleMillis) {
        if (settleMillis < 1L || settleMillis > 5_000L) {
            throw new IllegalArgumentException("settleMillis 必须落在 [1, 5000]");
        }
        // 每次调用都新建 holder：两个请求同时跑不会互相看到对方的标记位。
        // 注意必须用两个不同的类，而不是一个带泛型的类 —— 字段的 volatile 修饰
        // 是编译期属性，只有两个独立的 final 类才能让 C2 看到两种不同的内存语义。
        PlainStop plain = new PlainStop();
        VolatileStop vol = new VolatileStop();
        // 读线程退出时把自己的自旋次数放进来；-1 表示"还没出来"
        AtomicLong spins = new AtomicLong(-1L);

        Thread reader = new Thread(() -> {
            long n = useVolatile
                    ? spinOnVolatile(vol)
                    : spinOnPlain(plain);
            spins.set(n);
        }, "hoist-reader");
        reader.setDaemon(true);
        reader.start();

        long begin = System.nanoTime();
        try {
            // 必须等一会儿再置位：C2 是后台编译的，刚启动时读线程还在解释执行，
            // 那时无论用不用 volatile 都会立刻看到新值，测不出任何东西
            TimeUnit.MILLISECONDS.sleep(settleMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 JIT 编译时被中断", e);
        }

        log.info("【读提升实验】开始置位，useVolatile={}，已让读线程空转 {}ms", useVolatile, settleMillis);
        // 这里是唯一的变量：写同一个语义的标记位，只是字段一个是 volatile 一个不是
        if (useVolatile) {
            vol.stop = true;
        } else {
            plain.stop = true;
        }

        try {
            reader.join(HOIST_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待读线程退出时被中断", e);
        }

        long elapsedMillis = Math.max(1L, (System.nanoTime() - begin) / 1_000_000L);
        long actualSpins = spins.get();
        boolean sawWrite = actualSpins >= 0L && actualSpins < HOIST_SPIN_LIMIT;
        if (sawWrite) {
            log.info("【读提升实验】读线程看到了写入并退出：自旋 {} 次，耗时 {}ms。"
                            + "这就是 volatile 承诺的可见性", actualSpins, elapsedMillis);
        } else {
            log.warn("【读提升实验】读线程跑满 {} 次上限才退出：写入从未对它可见。"
                            + "原因是循环里那次字段读被 C2 提出了循环，循环体里再也不存在读内存这个动作",
                    HOIST_SPIN_LIMIT);
        }
        return new HoistResult(useVolatile, sawWrite, actualSpins, HOIST_SPIN_LIMIT,
                settleMillis, elapsedMillis);
    }

    /**
     * 干净自旋：条件里直接读普通字段，循环体只有自增。
     *
     * <p>单独写成方法而不是内联，是为了让 C2 在这里能看到一个单态的、无副作用的循环，
     * 从而真的去做读提升。别把它改成"调用 {@link PlainStop#get()}"——
     * 多一层方法调用，C2 就更可能放弃提升，实验立刻失去意义。
     *
     * @return 退出时累计的自旋次数
     */
    private static long spinOnPlain(PlainStop holder) {
        long spins = 0L;
        while (!holder.stop && spins < HOIST_SPIN_LIMIT) {
            spins++;
        }
        return spins;
    }

    /**
     * 干净自旋：与 {@link #spinOnPlain(PlainStop)} 逐字对应，只差字段的 volatile。
     *
     * <p>volatile 读自带"每次都必须从内存/缓存一致性协议拿最新值"的语义，
     * C2 不允许把它提出循环，所以这个循环一定能看到其他线程的写入。
     *
     * @return 退出时累计的自旋次数
     */
    private static long spinOnVolatile(VolatileStop holder) {
        long spins = 0L;
        while (!holder.stop && spins < HOIST_SPIN_LIMIT) {
            spins++;
        }
        return spins;
    }

    /**
     * 对照组容器：标记位是普通字段，读线程可能永远看不到主线程的写入。
     */
    private static final class PlainStop {

        private boolean stop;
    }

    /**
     * 实验组容器：标记位是 volatile 字段，写入对读线程立即可见。
     */
    private static final class VolatileStop {

        private volatile boolean stop;
    }

    /**
     * 读提升实验的结果。
     *
     * @param useVolatile  本次是否用了 volatile
     * @param sawWrite     读线程是否在上限之前看到了写入；false 说明读被提出了循环
     * @param spins        读线程实际自旋了多少次
     * @param spinLimit    上限值，{@code spins} 等于它就是"跑满了没看到"
     * @param settleMillis 置位前留给 JIT 的时间
     * @param elapsedMillis 从启动读线程到它退出（或被判定跑满）的耗时
     */
    public record HoistResult(boolean useVolatile,
                              boolean sawWrite,
                              long spins,
                              long spinLimit,
                              long settleMillis,
                              long elapsedMillis) {
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
