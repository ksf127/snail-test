package com.company.service.archlab.scenario.distributedtx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

/**
 * 分布式事务三种实现的对照实验：<b>AT / TCC / Saga</b>。
 *
 * <p>三个机制跑的是同一笔业务：建订单 → 扣库存 → 扣余额，
 * 只是"怎么保证一起成功或一起失败"的路径完全不同：
 * <table border="1">
 *   <caption>机制对照</caption>
 *   <tr><th>机制</th><th>一阶段</th><th>回滚依据</th><th>隔离性</th><th>侵入性</th></tr>
 *   <tr><td>AT</td><td>业务 SQL 直接提交 + undo log</td><td>前镜像</td><td>全局行锁</td><td>低</td></tr>
 *   <tr><td>TCC</td><td>Try 预占资源</td><td>Cancel 显式释放</td><td>预占态隔离</td><td>高（三个方法）</td></tr>
 *   <tr><td>Saga</td><td>正向步骤逐个提交</td><td>补偿动作</td><td>无</td><td>中（每步一个补偿）</td></tr>
 * </table>
 *
 * <p>每个机制都返回同一份终态字段，{@code compare(failAt)} 可以直接横向对比：
 * 成功时三者的库存/余额/订单都应一致；失败时都应该回到初始值，差别只在
 * "中间态是否可见"（{@code isolation}）、"回滚成本"（undo log vs Cancel vs 补偿次数）
 * 和"是否抢锁"（{@code lockConflicts}）。
 */
@Service
public class DistributedTxLab {

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
     * @param failAt 第几个分支失败（0 基：0=建订单，1=扣库存，2=扣余额），-1 表示全部成功
     */
    public Result run(Mechanism mechanism, int failAt) {
        if (mechanism == null) {
            throw new IllegalArgumentException("mechanism 不能为空");
        }
        if (failAt < -1 || failAt >= BRANCHES) {
            throw new IllegalArgumentException("failAt 必须在 [-1, " + (BRANCHES - 1) + "] 之间");
        }
        return switch (mechanism) {
            case AT -> runAt(failAt);
            case TCC -> runTcc(failAt);
            case SAGA -> runSaga(failAt);
        };
    }

    /** 三种机制跑同一场景，便于横向对比终态与回滚代价。 */
    public List<Result> compare(int failAt) {
        List<Result> results = new ArrayList<>(BRANCHES);
        for (Mechanism mechanism : Mechanism.values()) {
            results.add(run(mechanism, failAt));
        }
        return List.copyOf(results);
    }

    /**
     * AT：每个分支都是一次"本地写 + 写 undo log"，提交/回滚由全局事务统一决定。
     *
     * <p>订单也建成一行（0/1），这样三个分支都能被 undo log 覆盖，
     * 便于观察"回滚时每一步都被还原"。
     */
    private Result runAt(int failAt) {
        AtTransactionManager manager = new AtTransactionManager();
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
        return new Result(Mechanism.AT, Mechanism.AT.label(), run.committed(),
                consistent(run.committed(), stock, 0, balance, 0, orderPresent),
                stock, 0, balance, 0, orderPresent, run.branches(),
                run.undoLogsWritten(), run.undoLogsApplied(),
                run.undoLogsApplied(), 0, run.lockConflicts(), Mechanism.AT.isolation(),
                Math.max(run.elapsedMicros(), elapsed(start)), run.failure());
    }

    /**
     * TCC：Try 把资源从"可用"挪到"冻结"，Confirm 把冻结扣掉，Cancel 把冻结退回可用。
     *
     * <p>失败时只 Cancel 已经 Try 成功的分支（反向顺序）；未 Try 的分支在真实系统里
     * 也可能收到空回滚请求，这里为了对照清晰不主动触发。
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
            }
            for (int i : tried) {
                confirmBranch(state, i);
            }
            completed = true;
        } catch (RuntimeException e) {
            for (int i = tried.size() - 1; i >= 0; i--) {
                cancelBranch(state, tried.get(i));
            }
            failure = e.getMessage();
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
        return new Result(Mechanism.SAGA, Mechanism.SAGA.label(), run.completed(), consistent,
                state.stockAvailable, 0, state.balanceAvailable, 0, state.orderPresent,
                run.executed().size(), 0, 0, run.compensated().size(), run.compensationRetries(), 0,
                Mechanism.SAGA.isolation(), Math.max(run.elapsedMicros(), elapsed(start)), run.failure());
    }

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
