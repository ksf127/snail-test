package com.company.service.archlab.scenario.distributedtx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 分布式事务三种实现的对照实验：<b>AT / TCC / Saga</b>。
 *
 * <p>【解决什么问题】一笔下单要同时写三处数据（订单、库存、余额），
 * 三者不在同一个本地事务里。任意一步失败，另外两步必须能撤回去，
 * 否则就会出现"钱扣了但库存没扣""订单建了但库存没占"这类跨服务不一致。
 *
 * <p>【为什么这样设计】三个机制跑的是同一笔业务：建订单 → 扣库存 → 扣余额，
 * 只是"怎么保证一起成功或一起失败"的路径完全不同：
 * <table border="1">
 *   <caption>机制对照</caption>
 *   <tr><th>机制</th><th>一阶段</th><th>回滚依据</th><th>隔离性</th><th>侵入性</th><th>失败时怎么处理</th></tr>
 *   <tr><td>AT</td><td>业务 SQL 直接提交 + undo log</td><td>前镜像</td><td>全局行锁</td><td>低</td>
 *       <td>按 undo log 逆序还原；代价是全程持锁</td></tr>
 *   <tr><td>TCC</td><td>Try 预占资源</td><td>Cancel 显式释放</td><td>预占态隔离</td><td>高（三个方法）</td>
 *       <td>对已 Try 成功的分支反向 Cancel；代价是业务要写三套方法</td></tr>
 *   <tr><td>Saga</td><td>正向步骤逐个提交</td><td>补偿动作</td><td>无</td><td>中（每步一个补偿）</td>
 *       <td>反向执行补偿；代价是中间态可见、补偿还需自己保证幂等</td></tr>
 * </table>
 *
 * <p>【怎么读这段代码】入口是 {@link #run}：按 {@code failAt} 选择在哪一步失败，
 * 然后分派到 {@link #runAt} / {@link #runTcc} / {@link #runSaga}。
 * 三个方法都返回同一份 {@link Result}（库存、余额、订单终态 + 回滚计数），
 * 于是 {@link #compare} 可以把三份结果并排放在一起看：
 * 成功时三者的库存/余额/订单都应一致；失败时都应该回到初始值，差别只在
 * "中间态是否可见"（{@code isolation}）、"回滚成本"（undo log vs Cancel vs 补偿次数）
 * 和"是否抢锁"（{@code lockConflicts}）。
 */
@Service
public class DistributedTxLab {

    private static final Logger log = LoggerFactory.getLogger(DistributedTxLab.class);

    public static final long PRODUCT_ID = 1L;

    public static final long USER_ID = 42L;

    public static final long ORDER_ID = 1L;

    public static final int INITIAL_STOCK = 100;

    public static final int INITIAL_BALANCE = 10_000;

    public static final int QTY = 3;

    public static final int PRICE = 500;

    public static final int AMOUNT = QTY * PRICE;

    /** 分支数固定为 3：订单、库存、余额。 */
    private static final int BRANCHES = 3;

    private final SagaOrchestrator sagaOrchestrator = new SagaOrchestrator();

    public enum Mechanism {

        /** 自动补偿：一阶段直接提交，靠 undo log + 全局行锁。 */
        AT("AT", "全局行锁：未提交的中间态对其他事务不可见，但持锁到二阶段结束"),

        /** 手工补偿：Try 预占，Confirm/Cancel 显式处理。 */
        TCC("TCC", "预占隔离：中间态是可见的冻结份额，不会被别人超卖"),

        /** 长事务补偿：正向逐步提交，失败反向补偿。 */
        SAGA("Saga", "无隔离：任意时刻都可能读到「订单已建但库存未扣」的中间态");

        private final String label;

        private final String isolation;

        Mechanism(String label, String isolation) {
            this.label = label;
            this.isolation = isolation;
        }

        public String label() {
            return label;
        }

        public String isolation() {
            return isolation;
        }
    }

    public record Result(Mechanism mechanism,
                         String label,
                         boolean completed,
                         boolean consistent,
                         long stockAvailable,
                         long stockFrozen,
                         long balanceAvailable,
                         long balanceFrozen,
                         boolean orderPresent,
                         int branches,
                         int undoLogsWritten,
                         int undoLogsApplied,
                         int compensations,
                         int compensationRetries,
                         int lockConflicts,
                         String isolation,
                         long elapsedMicros,
                         String failure) {
    }

    /**
     * 跑一次全局事务。
     *
     * @param mechanism 选用哪种分布式事务机制
     * @param failAt    第几个分支失败（0 基：0=建订单，1=扣库存，2=扣余额），-1 表示全部成功
     * @return 本次实验的终态与回滚统计
     * @throws IllegalArgumentException mechanism 为空或 failAt 越界
     */
    public Result run(Mechanism mechanism, int failAt) {
        if (mechanism == null) {
            throw new IllegalArgumentException("mechanism 不能为空");
        }
        if (failAt < -1 || failAt >= BRANCHES) {
            throw new IllegalArgumentException("failAt 必须在 [-1, " + (BRANCHES - 1) + "] 之间");
        }
        // 先打入口日志：failAt 是复现失败场景的唯一开关，出了问题先看这一行
        log.info("【分布式事务实验】测什么：一笔「建订单 → 扣库存 → 扣余额」在 failAt={} 时的提交/回滚路径；"
                        + "本次 mechanism={}（{}）", failAt, mechanism.label(), mechanism.isolation());
        Result result = switch (mechanism) {
            case AT -> runAt(failAt);
            case TCC -> runTcc(failAt);
            case SAGA -> runSaga(failAt);
        };
        log.info("【分布式事务实验】结束: mechanism={}, completed={}, consistent={}, failure={}；判读：{}",
                result.mechanism(), result.completed(), result.consistent(), result.failure(),
                result.consistent()
                        ? (result.completed()
                                ? "全部成功且终态一致：库存与余额已扣减、订单存在、无冻结残留"
                                : "失败已完整回滚：库存与余额回到初始值、订单不存在、无冻结残留")
                        : "终态不一致：存在未回滚的分支或冻结残留，说明补偿没有覆盖全部已执行分支");
        return result;
    }

    /**
     * 三种机制跑同一场景，便于横向对比终态与回滚代价。
     *
     * @param failAt 第几个分支失败（0 基），-1 表示全部成功
     * @return 固定 3 条结果，顺序与 {@link Mechanism#values()} 一致
     */
    public List<Result> compare(int failAt) {
        List<Result> results = new ArrayList<>(BRANCHES);
        log.info("【分布式事务实验】测什么：同一笔 failAt={} 的业务在 AT / TCC / Saga 三种机制下的终态与回滚代价",
                failAt);
        log.info("【分布式事务实验】谁和谁对照：A组 AT（undo log 回滚）、B组 TCC（Cancel 释放冻结）、"
                + "C组 Saga（反向补偿）；三组跑同一 failAt={}", failAt);
        for (Mechanism mechanism : Mechanism.values()) {
            results.add(run(mechanism, failAt));
        }
        // 把三种机制的终态打在同一行日志里，方便直接对照"谁回滚成功了、谁残留了中间态"
        log.info("【分布式事务实验】横向对比完成: failAt={}, 结果={}", failAt,
                results.stream()
                        .map(r -> r.mechanism() + "[completed=" + r.completed()
                                + ", consistent=" + r.consistent()
                                + ", stock=" + r.stockAvailable()
                                + ", balance=" + r.balanceAvailable()
                                + ", order=" + r.orderPresent() + "]")
                        .toList());
        boolean allConsistent = results.stream().allMatch(Result::consistent);
        // 顺序与 Mechanism.values() 一致：0=AT，1=TCC，2=Saga，这里直接按下标取回滚代价
        Result at = results.get(0);
        Result tcc = results.get(1);
        Result saga = results.get(2);
        log.info("【分布式事务实验】判读：{}；回滚代价 AT=undoLog 回放 {} 条/锁冲突 {} 次，"
                        + "TCC=Cancel {} 次/锁冲突 {} 次，Saga=补偿 {} 次（含重试 {} 次）；"
                        + "三者都靠补偿换一致性，差别在中间态是否可见与回滚成本",
                allConsistent ? "三种机制终态均与业务预期一致" : "存在终态不一致的机制，见上方各自结果",
                at.undoLogsApplied(), at.lockConflicts(),
                tcc.compensations(), tcc.lockConflicts(),
                saga.compensations(), saga.compensationRetries());
        return List.copyOf(results);
    }

    /**
     * AT：每个分支都是一次"本地写 + 写 undo log"，提交/回滚由全局事务统一决定。
     *
     * <p>订单也建成一行（0/1），这样三个分支都能被 undo log 覆盖，
     * 便于观察"回滚时每一步都被还原"。
     *
     * @param failAt 第几个分支失败（0 基），-1 表示全部成功
     * @return AT 的终态；回滚成功后库存/余额/订单都应回到初始值
     */
    private Result runAt(int failAt) {
        AtTransactionManager manager = new AtTransactionManager();
        // 用三行数据模拟三个微服务各自的库：resource 相当于库名，key 相当于主键
        manager.seed("order", ORDER_ID, 0);
        manager.seed("stock", PRODUCT_ID, INITIAL_STOCK);
        manager.seed("balance", USER_ID, INITIAL_BALANCE);
        List<AtTransactionManager.AtBranch> branches = List.of(
                new AtTransactionManager.AtBranch("order", ORDER_ID, 1),
                new AtTransactionManager.AtBranch("stock", PRODUCT_ID, -QTY),
                new AtTransactionManager.AtBranch("balance", USER_ID, -AMOUNT));

        long start = System.nanoTime();
        AtTransactionManager.AtRun run = manager.run("order:" + ORDER_ID, branches, failAt);
        long stock = manager.valueOf("stock", PRODUCT_ID);
        long balance = manager.valueOf("balance", USER_ID);
        boolean orderPresent = manager.valueOf("order", ORDER_ID) == 1;
        log.info("【AT】分支执行 {} 个, undoLog 写入 {} 条/回放 {} 条, 锁冲突 {}, 最终 committed={}",
                run.branches(), run.undoLogsWritten(), run.undoLogsApplied(),
                run.lockConflicts(), run.committed());
        return new Result(Mechanism.AT, Mechanism.AT.label(), run.committed(),
                consistent(run.committed(), stock, 0, balance, 0, orderPresent),
                stock, 0, balance, 0, orderPresent, run.branches(),
                run.undoLogsWritten(), run.undoLogsApplied(),
                // AT 不用业务补偿：undo log 回放次数就是它实际做的"回滚动作"次数
                run.undoLogsApplied(), 0, run.lockConflicts(), Mechanism.AT.isolation(),
                Math.max(run.elapsedMicros(), elapsed(start)), run.failure());
    }

    /**
     * TCC：Try 把资源从"可用"挪到"冻结"，Confirm 把冻结扣掉，Cancel 把冻结退回可用。
     *
     * <p>失败时只 Cancel 已经 Try 成功的分支（反向顺序）；未 Try 的分支在真实系统里
     * 也可能收到空回滚请求，这里为了对照清晰不主动触发。
     *
     * @param failAt 第几个分支失败（0 基），-1 表示全部成功
     * @return TCC 的终态；失败且 Cancel 正常时冻结份额应归零
     */
    private Result runTcc(int failAt) {
        TccState state = new TccState();
        List<Integer> tried = new ArrayList<>();
        boolean completed = false;
        String failure = null;
        long start = System.nanoTime();
        try {
            for (int i = 0; i < BRANCHES; i++) {
                if (i == failAt) {
                    throw new IllegalStateException("模拟第 " + (i + 1) + " 个分支 Try 失败");
                }
                tryBranch(state, i);
                tried.add(i);
                log.info("【TCC】Try 成功: branch={}, stockAvailable={}, stockFrozen={}, balanceFrozen={}",
                        i, state.stockAvailable, state.stockFrozen, state.balanceFrozen);
            }
            for (int i : tried) {
                confirmBranch(state, i);
                log.info("【TCC】Confirm 完成: branch={}, stockFrozen={}, balanceFrozen={}",
                        i, state.stockFrozen, state.balanceFrozen);
            }
            completed = true;
        } catch (RuntimeException e) {
            // 只 Cancel 已经 Try 成功的分支，且必须反向：后面的步骤依赖前面的结果
            for (int i = tried.size() - 1; i >= 0; i--) {
                cancelBranch(state, tried.get(i));
                log.info("【TCC】Cancel 完成: branch={}, stockAvailable={}, balanceAvailable={}",
                        tried.get(i), state.stockAvailable, state.balanceAvailable);
            }
            failure = e.getMessage();
            log.warn("【TCC】分支失败，已反向 Cancel {} 个分支: {}", tried.size(), failure);
        }
        return new Result(Mechanism.TCC, Mechanism.TCC.label(), completed,
                consistent(completed, state.stockAvailable, state.stockFrozen,
                        state.balanceAvailable, state.balanceFrozen, state.orderPresent),
                state.stockAvailable, state.stockFrozen, state.balanceAvailable, state.balanceFrozen,
                state.orderPresent, tried.size(), 0, 0,
                completed ? 0 : tried.size(), 0, 0, Mechanism.TCC.isolation(), elapsed(start), failure);
    }

    /**
     * Saga：正向步骤逐个提交，失败后反向执行补偿。
     *
     * <p>补偿也有失败的可能，{@link SagaOrchestrator} 会重试并把最终失败的步骤记下来。
     *
     * @param failAt 第几个步骤失败（0 基），-1 表示全部成功
     * @return Saga 的终态；补偿全部成功时订单/库存/余额应回到初始值
     */
    private Result runSaga(int failAt) {
        SagaState state = new SagaState();
        List<SagaOrchestrator.SagaStep> steps = List.of(
                new SagaOrchestrator.SagaStep("createOrder",
                        () -> state.orderPresent = true,
                        () -> state.orderPresent = false),
                new SagaOrchestrator.SagaStep("deductStock",
                        () -> state.stockAvailable -= QTY,
                        () -> state.stockAvailable += QTY),
                new SagaOrchestrator.SagaStep("deductBalance",
                        () -> state.balanceAvailable -= AMOUNT,
                        () -> state.balanceAvailable += AMOUNT));
        long start = System.nanoTime();
        SagaOrchestrator.SagaRun run = sagaOrchestrator.run("order:" + ORDER_ID, steps, failAt);
        // 补偿失败时终态没有还原，consistent 自然为 false，无需额外判断失败的补偿。
        boolean consistent = consistent(run.completed(), state.stockAvailable, 0,
                state.balanceAvailable, 0, state.orderPresent);
        log.info("【Saga】正向执行 {} 步, 补偿成功 {} 步, 补偿重试 {} 次, completed={}",
                run.executed().size(), run.compensated().size(), run.compensationRetries(), run.completed());
        return new Result(Mechanism.SAGA, Mechanism.SAGA.label(), run.completed(), consistent,
                state.stockAvailable, 0, state.balanceAvailable, 0, state.orderPresent,
                run.executed().size(), 0, 0, run.compensated().size(), run.compensationRetries(), 0,
                Mechanism.SAGA.isolation(), Math.max(run.elapsedMicros(), elapsed(start)), run.failure());
    }

    /**
     * TCC 的 Try：只做预占，不真正扣减；库存/余额都从"可用"挪到"冻结"。
     */
    private static void tryBranch(TccState state, int branch) {
        switch (branch) {
            case 0 -> state.orderPresent = true;
            case 1 -> {
                state.stockAvailable -= QTY;
                state.stockFrozen += QTY;
            }
            case 2 -> {
                state.balanceAvailable -= AMOUNT;
                state.balanceFrozen += AMOUNT;
            }
            default -> throw new IllegalStateException("未知分支: " + branch);
        }
    }

    /**
     * TCC 的 Confirm：把冻结份额真正扣掉。必须幂等，真实实现还要防重复 Confirm。
     */
    private static void confirmBranch(TccState state, int branch) {
        switch (branch) {
            case 0 -> {
                // 订单 Confirm 不需要额外动作，状态由"已创建"变为"已确认"
            }
            case 1 -> state.stockFrozen -= QTY;
            case 2 -> state.balanceFrozen -= AMOUNT;
            default -> throw new IllegalStateException("未知分支: " + branch);
        }
    }

    /**
     * TCC 的 Cancel：把冻结份额还给可用。必须幂等，且要能处理"空回滚"。
     */
    private static void cancelBranch(TccState state, int branch) {
        switch (branch) {
            case 0 -> state.orderPresent = false;
            case 1 -> {
                state.stockAvailable += QTY;
                state.stockFrozen -= QTY;
            }
            case 2 -> {
                state.balanceAvailable += AMOUNT;
                state.balanceFrozen -= AMOUNT;
            }
            default -> throw new IllegalStateException("未知分支: " + branch);
        }
    }

    /**
     * 一致性判定：成功时三处数据都要变成"扣减后的值"，失败时都要回到初始值，
     * 且不允许残留任何冻结份额（残留说明 Cancel/Confirm 少执行了一次）。
     */
    private static boolean consistent(boolean completed, long stockAvailable, long stockFrozen,
                                      long balanceAvailable, long balanceFrozen, boolean orderPresent) {
        if (stockFrozen != 0 || balanceFrozen != 0) {
            return false;
        }
        if (completed) {
            return stockAvailable == INITIAL_STOCK - QTY
                    && balanceAvailable == INITIAL_BALANCE - AMOUNT
                    && orderPresent;
        }
        return stockAvailable == INITIAL_STOCK
                && balanceAvailable == INITIAL_BALANCE
                && !orderPresent;
    }

    private static long elapsed(long startNanos) {
        return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startNanos);
    }

    /** TCC 的账户/库存/订单状态：可用与冻结分开，是"预占"能看见的部分。 */
    private static final class TccState {

        private int stockAvailable = INITIAL_STOCK;

        private int stockFrozen;

        private int balanceAvailable = INITIAL_BALANCE;

        private int balanceFrozen;

        private boolean orderPresent;
    }

    /** Saga 只有终态值，没有冻结概念——这正是它缺少隔离的表现。 */
    private static final class SagaState {

        private int stockAvailable = INITIAL_STOCK;

        private int balanceAvailable = INITIAL_BALANCE;

        private boolean orderPresent;
    }
}
