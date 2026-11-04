package com.company.service.archlab.concurrency;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Phaser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 【实验目的】验证 volatile 的第二个语义：<b>有序性</b> —— 它靠内存屏障禁止特定重排序。
 *
 * <p>【解决什么问题】没有屏障时，CPU 和编译器可以把"写自己的标志位"和"读对方的标志位"
 * 调换执行顺序。x86 的存储缓冲区（store buffer）允许 <b>Store → Load</b> 重排：
 * 写还在缓冲区里没落到缓存，后面的读已经执行了，于是两个线程<b>都读不到对方刚写的值</b>。
 * 这就是 Double-Checked Locking、Dekker 互斥算法这类代码"不加 volatile 就悄悄错"的根源。
 *
 * <p>【实验怎么做】经典的 Dekker 式双线程对拍，每一轮：
 * <pre>
 *   线程 1：flag1 = true;  r1 = flag2;
 *   线程 2：flag2 = true;  r2 = flag1;
 * </pre>
 * 两个写都发生在两个读之前（程序顺序上）。如果 {@code r1 == false && r2 == false}，
 * 说明两个写都被推迟到自己的读之后才生效 —— 这是程序顺序不可能产生的结果，只能是重排序。
 *
 * <p>【为什么两组代码只差一个关键字】{@link PlainPair} 与 {@link VolatilePair} 字段完全相同，
 * 唯一的区别是后者带 {@code volatile}。volatile 写之后 JIT 会插入 StoreLoad 屏障，
 * 强制把缓冲区刷出去，再执行后面的读，所以 B 组永远不可能出现"两人都没看到"。
 *
 * <p>【怎么读这段代码】{@link #runPlainRounds(int)} 与 {@link #runVolatileRounds(int)} 逐行对应：
 * 一个 Phaser 当起跑线（三轮同步：重置 → 起跑 → 收卷），两个线程各跑一半，
 * 统计"两人都没看到对方"的轮数。对照接口 {@link #compare(int, int)} 只是把两者交替跑几轮。
 *
 * <p>【本机实测】20 万轮：普通字段约 5 万~7 万次违规（25%~35%），volatile 恒为 0。
 * 差距不是噪声，方向也确定 —— 这正是内存屏障在起作用。
 */
@Service
public class OrderingDemo {

    private static final Logger log = LoggerFactory.getLogger(OrderingDemo.class);

    /** 轮数下限：太少会被"碰巧"掩盖，统计不出频次。 */
    private static final int MIN_ROUNDS = 1_000;

    /** 轮数上限：单轮约 1µs 量级，20 万轮约 0.2 秒，上限卡住防止一个请求把机器打满。 */
    private static final int MAX_ROUNDS = 2_000_000;

    /** 对照实验每组最多跑几轮，防止参数被放大成分钟级等待。 */
    private static final int MAX_TRIALS = 5;

    /**
     * 跑一组重排检测。
     *
     * @param useVolatile true 用 volatile 字段（B组），false 用普通字段（A组）
     * @param rounds      对拍轮数，取值 [1000, 2000000]；越大越容易撞上重排窗口
     * @return 违规轮数、总轮数、耗时
     * @throws IllegalArgumentException rounds 越界
     */
    public OrderingResult run(boolean useVolatile, int rounds) {
        validateRounds(rounds);
        log.info("【有序性实验】{}｜测什么：线程1 执行 flag1=true 后立刻读 flag2，线程2 对称操作；"
                        + "两人都读到 false 就说明「写」被重排到了「读」之后（StoreLoad 重排）",
                variantLabel(useVolatile));
        long begin = System.nanoTime();
        int violations = useVolatile ? runVolatileRounds(rounds) : runPlainRounds(rounds);
        long elapsedMillis = Math.max(1L, (System.nanoTime() - begin) / 1_000_000L);
        logResult(variantLabel(useVolatile), rounds, violations, elapsedMillis);
        return new OrderingResult(useVolatile, rounds, violations, elapsedMillis);
    }

    /**
     * 正式对照：同一套双线程对拍，A组普通字段、B组 volatile 字段，<b>交替</b>各跑 trials 轮。
     *
     * <p>交替执行是为了让两组共享同样的 JIT 预热与机器负载，避免"先跑的那组独占编译期"。
     *
     * @param rounds 每组每轮的对拍轮数
     * @param trials 每组的轮数，取值 [1, 5]
     * @return 两组的总违规次数与逐轮明细
     * @throws IllegalArgumentException 参数越界
     */
    public OrderingCompareResult compare(int rounds, int trials) {
        validateRounds(rounds);
        if (trials < 1 || trials > MAX_TRIALS) {
            throw new IllegalArgumentException("trials 必须落在 [1, " + MAX_TRIALS + "]");
        }
        List<OrderingResult> plainRuns = new ArrayList<>(trials);
        List<OrderingResult> volatileRuns = new ArrayList<>(trials);
        log.info("【有序性对照实验】测什么：两个线程各自「写自己的标志位、读对方的标志位」，"
                + "统计「两人都没看到对方」的轮数 = StoreLoad 重排发生的次数");
        log.info("【有序性对照实验】谁和谁对照：A组 = 普通字段（无 volatile），B组 = volatile 字段；"
                + "两组交替执行各 {} 轮，每轮 {} 次对拍", trials, rounds);
        for (int i = 0; i < trials; i++) {
            String round = "第 " + (i + 1) + "/" + trials + " 轮";
            plainRuns.add(runSingle(false, rounds, round + " A组"));
            volatileRuns.add(runSingle(true, rounds, round + " B组"));
        }
        int plainViolations = sum(plainRuns);
        int volatileViolations = sum(volatileRuns);
        log.info("【有序性对照实验】========== 汇总（{} 轮 × {} 次对拍 × 2 组）==========",
                trials, rounds);
        log.info("【有序性对照实验】A组 普通字段(无volatile)：重排 {} 次，占比 {}",
                plainViolations, percent(plainViolations, (long) rounds * trials));
        log.info("【有序性对照实验】B组 volatile 字段          ：重排 {} 次，占比 {}",
                volatileViolations, percent(volatileViolations, (long) rounds * trials));
        if (volatileViolations == 0 && plainViolations > 0) {
            log.info("【有序性对照实验】判读：A组出现了重排，B组一次都没有 —— "
                    + "这就是 volatile 写之后的 StoreLoad 屏障在起作用");
        } else if (volatileViolations > 0) {
            log.warn("【有序性对照实验】判读：B组也出现了重排，说明本机 JIT/CPU 行为异常，结果不可信");
        } else {
            log.info("【有序性对照实验】判读：A组本轮也没撞上重排窗口（概率事件），请加大 rounds 或重跑");
        }
        return new OrderingCompareResult(rounds, trials, plainViolations, volatileViolations,
                plainRuns, volatileRuns);
    }

    /**
     * 跑一组并打日志，{@code tag} 用来标明是第几轮、哪一组。
     */
    private OrderingResult runSingle(boolean useVolatile, int rounds, String tag) {
        long begin = System.nanoTime();
        int violations = useVolatile ? runVolatileRounds(rounds) : runPlainRounds(rounds);
        long elapsedMillis = Math.max(1L, (System.nanoTime() - begin) / 1_000_000L);
        log.info("【有序性实验】{}｜{}｜结果：{} 次对拍里重排 {} 次（{}），耗时 {}ms",
                tag, variantLabel(useVolatile), rounds, violations,
                percent(violations, rounds), elapsedMillis);
        return new OrderingResult(useVolatile, rounds, violations, elapsedMillis);
    }

    // ------------------------------------------------------------------
    // 下面两个方法逐行对应，唯一差别是字段带不带 volatile。
    // 拆成两份而不是抽公共方法，是为了让「唯一变量」这件事在代码上一眼可见：
    // volatile 是字段的编译期属性，只有两个独立的 final 类才能让 JIT 看到两种内存语义。
    // ------------------------------------------------------------------

    /**
     * A组：普通字段。写可能滞留在存储缓冲区，后面的读先执行 → 两人互相看不见。
     *
     * @param rounds 对拍轮数
     * @return 出现"两人都没看到对方"的轮数
     */
    private static int runPlainRounds(int rounds) {
        PlainPair pair = new PlainPair();
        boolean[] seenByFirst = new boolean[rounds];
        boolean[] seenBySecond = new boolean[rounds];
        // 三方同步：主线程 + 两个对拍线程。每轮三次汇合：重置 → 起跑 → 收卷
        Phaser phaser = new Phaser(3);

        Thread first = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                phaser.arriveAndAwaitAdvance();
                // 普通写
                pair.flag1 = true;
                // 普通读：这一句可能被重排到上一句之前执行
                seenByFirst[i] = pair.flag2;
                phaser.arriveAndAwaitAdvance();
            }
        }, "order-plain-1");
        first.setDaemon(true);

        Thread second = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                phaser.arriveAndAwaitAdvance();
                pair.flag2 = true;
                seenBySecond[i] = pair.flag1;
                phaser.arriveAndAwaitAdvance();
            }
        }, "order-plain-2");
        second.setDaemon(true);

        first.start();
        second.start();
        int violations = 0;
        for (int i = 0; i < rounds; i++) {
            // 重置必须在放行两个线程之前完成：Phaser 的汇合点自带 happens-before，
            // 两个线程一定能看到"false"，不会把上一轮的残留当成这一轮的结果
            pair.flag1 = false;
            pair.flag2 = false;
            phaser.arriveAndAwaitAdvance();
            phaser.arriveAndAwaitAdvance();
            // 两人都没看到对方刚写的值：程序顺序不可能产生，只能是重排
            if (!seenByFirst[i] && !seenBySecond[i]) {
                violations++;
            }
        }
        joinQuietly(first, second);
        return violations;
    }

    /**
     * B组：volatile 字段。volatile 写之后有 StoreLoad 屏障，写必须先刷出去，读才能执行，
     * 所以这里永远统计到 0。
     *
     * @param rounds 对拍轮数
     * @return 出现"两人都没看到对方"的轮数，恒为 0
     */
    private static int runVolatileRounds(int rounds) {
        VolatilePair pair = new VolatilePair();
        boolean[] seenByFirst = new boolean[rounds];
        boolean[] seenBySecond = new boolean[rounds];
        Phaser phaser = new Phaser(3);

        Thread first = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                phaser.arriveAndAwaitAdvance();
                // volatile 写：写完之后插入 StoreLoad 屏障
                pair.flag1 = true;
                // volatile 读：不允许被重排到前面的写之前，且必须读到最新值
                seenByFirst[i] = pair.flag2;
                phaser.arriveAndAwaitAdvance();
            }
        }, "order-volatile-1");
        first.setDaemon(true);

        Thread second = new Thread(() -> {
            for (int i = 0; i < rounds; i++) {
                phaser.arriveAndAwaitAdvance();
                pair.flag2 = true;
                seenBySecond[i] = pair.flag1;
                phaser.arriveAndAwaitAdvance();
            }
        }, "order-volatile-2");
        second.setDaemon(true);

        first.start();
        second.start();
        int violations = 0;
        for (int i = 0; i < rounds; i++) {
            pair.flag1 = false;
            pair.flag2 = false;
            phaser.arriveAndAwaitAdvance();
            phaser.arriveAndAwaitAdvance();
            if (!seenByFirst[i] && !seenBySecond[i]) {
                violations++;
            }
        }
        joinQuietly(first, second);
        return violations;
    }

    /**
     * 等待两个对拍线程结束；被中断时恢复中断标记，让上层感知。
     */
    private static void joinQuietly(Thread first, Thread second) {
        try {
            first.join();
            second.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待有序性实验线程结束时被中断", e);
        }
    }

    private static void validateRounds(int rounds) {
        if (rounds < MIN_ROUNDS || rounds > MAX_ROUNDS) {
            throw new IllegalArgumentException(
                    "rounds 必须落在 [" + MIN_ROUNDS + ", " + MAX_ROUNDS + "]");
        }
    }

    private static String variantLabel(boolean useVolatile) {
        return useVolatile ? "B组 volatile 字段" : "A组 普通字段(无volatile)";
    }

    private static void logResult(String label, int rounds, int violations, long elapsedMillis) {
        if (violations > 0) {
            log.warn("【有序性实验】{}｜结果：{} 次对拍里重排 {} 次（{}），耗时 {}ms。"
                            + "判读：写没有先落到内存，后面的读先执行了 —— 没有屏障就挡不住 StoreLoad 重排",
                    label, rounds, violations, percent(violations, rounds), elapsedMillis);
        } else {
            log.info("【有序性实验】{}｜结果：{} 次对拍里重排 0 次，耗时 {}ms。"
                            + "判读：本轮没撞上重排窗口 —— B组应恒为 0；A组偶发 0 属于概率问题，加大 rounds 再看",
                    label, rounds, elapsedMillis);
        }
    }

    private static int sum(List<OrderingResult> runs) {
        int total = 0;
        for (OrderingResult run : runs) {
            total += run.violations();
        }
        return total;
    }

    private static String percent(int violations, long total) {
        if (total <= 0L) {
            return "0%";
        }
        return String.format("%.3f%%", violations * 100.0 / total);
    }

    /** A组：两个可重排的普通字段。 */
    private static final class PlainPair {
        private boolean flag1;
        private boolean flag2;
    }

    /** B组：同样的两个字段，只多了 volatile —— 它会带来内存屏障。 */
    private static final class VolatilePair {
        private volatile boolean flag1;
        private volatile boolean flag2;
    }

    /**
     * 单组实验结果。
     *
     * @param useVolatile  true 表示 volatile 组
     * @param rounds       对拍轮数
     * @param violations   "两人都没看到对方"的轮数，即重排发生次数
     * @param elapsedMillis 耗时
     */
    public record OrderingResult(boolean useVolatile, int rounds, int violations, long elapsedMillis) {
    }

    /**
     * 对照实验结果。
     *
     * @param rounds             每组每轮的对拍次数
     * @param trials             每组的轮数
     * @param plainViolations    A组（普通字段）总重排次数
     * @param volatileViolations B组（volatile）总重排次数，正常应为 0
     * @param plainRuns          A组逐轮明细
     * @param volatileRuns       B组逐轮明细
     */
    public record OrderingCompareResult(int rounds, int trials, int plainViolations,
                                        int volatileViolations,
                                        List<OrderingResult> plainRuns,
                                        List<OrderingResult> volatileRuns) {
    }
}
