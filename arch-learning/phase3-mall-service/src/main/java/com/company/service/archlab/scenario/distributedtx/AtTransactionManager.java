package com.company.service.archlab.scenario.distributedtx;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seata AT 的最小可运行复刻：<b>全局行锁 + undo log + 两阶段提交/回滚</b>。
 *
 * <p>【解决什么问题】跨库的业务写没法用单个本地事务兜住。AT 的思路是"先照常提交，
 * 出问题再按镜像撤回"：每个分支在本地事务里直接把数据改掉，同时把改之前的旧值
 * （前镜像）写进 undo log；全局事务失败时，用前镜像把每一步改回去。
 *
 * <p>【为什么这样设计】一阶段就提交，意味着必须解决"别人改了我这一行怎么办"，
 * 于是引入<b>全局行锁</b>：写之前先按 {@code resource:主键} 抢占，持锁到二阶段结束，
 * 保证回滚时数据还是自己写进去的样子。代价是锁的持有时间长、并发度低，
 * 换来的是业务代码零侵入 —— 这正是 AT 和 TCC 最大的差别。
 *
 * <p>【怎么读这段代码】调用顺序固定为
 * {@link #begin} → 若干次 {@link #branch} → {@link #commit} 或 {@link #rollback}。
 * 状态只会按 {@code OPEN → COMMITTED} 或 {@code OPEN → ROLLED_BACK} 迁移，
 * 重复结束同一个事务会直接抛异常（{@link #requireOpen}）。
 * {@link #run} 是把这套顺序包装成一次完整实验的便捷入口。
 *
 * <p>它不是 Seata 的替代品，而是把 AT 的三条核心机制摊开，方便和 TCC / Saga 对照：
 * <ol>
 *   <li><b>一阶段</b>：业务 SQL 直接执行并提交本地事务，同时把"前镜像/后镜像"写进 undo log。
 *       所以 AT 对业务代码几乎零侵入 —— 这也是它和 TCC 最大的差别。</li>
 *   <li><b>全局行锁</b>：一阶段提交前先按 {@code resource:主键} 抢全局锁，持有到二阶段结束。
 *       没有它，另一个全局事务就能在同一行上覆盖写，undo log 回滚时会把别人的修改一起抹掉。</li>
 *   <li><b>二阶段</b>：全局提交只删 undo log（极快）；全局回滚则按 undo log 逆序还原，
 *       还原前先校验"后镜像"是否仍等于当前值，不等就是脏写，必须告警而不是硬覆盖。</li>
 * </ol>
 *
 * <p>真实 Seata 里这些动作由 {@code ConnectionProxy} / {@code UndoLogManager} / TC 完成，
 * 应用只加一个 {@code @GlobalTransactional}；这里显式写成 {@link #begin} / {@link #branch}
 * / {@link #commit} / {@link #rollback}，是为了让每一步的代价和边界都看得见。
 *
 * <p>换成真实 Seata 的接法（需要独立 TC 服务，默认不启用）：
 * <pre>
 *   io.seata:seata-spring-boot-starter:2.0.0
 *   seata.tx-service-group / seata.service.grouplist.default=127.0.0.1:8091
 *   业务库建 undo_log 表，入口方法加 @GlobalTransactional
 * </pre>
 */
public class AtTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(AtTransactionManager.class);

    /** 一行数据：resource 相当于库名，key 相当于主键。 */
    public record AtBranch(String resource, long key, long delta) {
    }

    /** 二阶段回滚需要的最小信息，真实实现里是 undo_log 表的 before/after 两列 JSON。 */
    public record UndoLog(String xid, String resource, long key, long before, long after) {
    }

    public enum Status {
        OPEN,
        COMMITTED,
        ROLLED_BACK
    }

    public record AtRun(boolean committed,
                        int branches,
                        int undoLogsWritten,
                        int undoLogsApplied,
                        int lockConflicts,
                        int dirtyWrites,
                        long elapsedMicros,
                        String failure) {
    }

    public record AtStats(long commits,
                          long rollbacks,
                          long lockConflicts,
                          long dirtyWrites,
                          long undoLogsWritten,
                          long undoLogsApplied,
                          int activeLocks) {
    }

    private final Map<String, Long> rows = new ConcurrentHashMap<>();

    /** rowId -> 持有它的全局事务 xid，等价于 TC 侧的全局锁表。 */
    private final Map<String, String> globalLocks = new ConcurrentHashMap<>();

    private final AtomicLong xidSequence = new AtomicLong();

    private final LongAdder commits = new LongAdder();

    private final LongAdder rollbacks = new LongAdder();

    private final LongAdder lockConflicts = new LongAdder();

    private final LongAdder dirtyWrites = new LongAdder();

    private final LongAdder undoLogsWritten = new LongAdder();

    private final LongAdder undoLogsApplied = new LongAdder();

    /**
     * 预置一行初始数据，相当于建库时插好的种子数据。
     *
     * @param resource 资源名（模拟库名）
     * @param key      主键
     * @param value    初始值
     */
    public void seed(String resource, long key, long value) {
        rows.put(rowId(resource, key), value);
    }

    /**
     * 读当前值，不存在时返回 0。
     *
     * @param resource 资源名
     * @param key      主键
     * @return 当前值
     */
    public long valueOf(String resource, long key) {
        return rows.getOrDefault(rowId(resource, key), 0L);
    }

    /**
     * 绕过 Seata 代理的本地写入。真实场景里它可能是"没接 Seata 的另一个服务"，
     * 正是脏写与"回滚覆盖别人修改"的来源，这里用它来演示后镜像校验。
     *
     * @param resource 资源名
     * @param key      主键
     * @param value    要写入的值
     */
    public void localWrite(String resource, long key, long value) {
        rows.put(rowId(resource, key), value);
    }

    /**
     * 开启一个全局事务，返回带唯一 xid 的上下文。
     *
     * <p>幂等性：不幂等，每次调用都会生成新的 xid 并自增序列。
     *
     * @param businessId 业务标识，只用于拼出可读的 xid
     * @return 新的全局事务上下文，状态为 {@code OPEN}
     */
    public GlobalTransaction begin(String businessId) {
        GlobalTransaction tx = new GlobalTransaction("AT:" + businessId + ":" + xidSequence.incrementAndGet());
        log.info("【AT】开启全局事务: xid={}", tx.xid());
        return tx;
    }

    /**
     * 一阶段：抢全局锁 → 执行业务写 → 记录 undo log。
     *
     * <p>锁放在写之前，是为了让"后镜像"在被写坏之前就挡住并发的全局事务。
     *
     * <p>幂等性：不幂等。重复调用会重复叠加 delta 并多记一条 undo log，
     * 调用方必须保证同一分支只执行一次。
     *
     * @param tx     全局事务上下文，必须处于 {@code OPEN}
     * @param branch 一个分支：对哪个资源的哪一行做多少增减
     * @throws AtLockConflictException 该行已被其他全局事务锁定
     * @throws IllegalStateException   全局事务已结束
     */
    public void branch(GlobalTransaction tx, AtBranch branch) {
        requireOpen(tx);
        String rowId = rowId(branch.resource(), branch.key());
        // putIfAbsent 就是"抢锁"：抢不到说明别的全局事务正在改这一行
        String owner = globalLocks.putIfAbsent(rowId, tx.xid());
        if (owner != null && !owner.equals(tx.xid())) {
            lockConflicts.increment();
            log.warn("【AT】全局锁冲突: xid={}, row={}, 持有者={}", tx.xid(), rowId, owner);
            throw new AtLockConflictException("行 " + rowId + " 已被全局事务 " + owner + " 锁定");
        }
        tx.lockedRows.add(rowId);

        // 前镜像 = 改之前的值，回滚时靠它还原；后镜像用于回滚前校验有没有被脏写
        long before = rows.getOrDefault(rowId, 0L);
        long after = before + branch.delta();
        rows.put(rowId, after);
        tx.undoLogs.add(new UndoLog(tx.xid(), branch.resource(), branch.key(), before, after));
        undoLogsWritten.increment();
        log.info("【AT】一阶段分支完成: xid={}, row={}, before={}, after={}",
                tx.xid(), rowId, before, after);
    }

    /**
     * 二阶段全局提交：本地数据在一阶段已经落地，这里只清 undo log 并释放全局锁。
     *
     * <p>幂等性：不幂等，事务已结束后再调用会抛异常。
     *
     * @param tx 全局事务上下文
     * @throws IllegalStateException 全局事务已结束
     */
    public void commit(GlobalTransaction tx) {
        requireOpen(tx);
        // 提交不需要还原数据，只要把 undo log 丢掉并放锁 —— 所以 AT 的提交非常快
        int lockCount = tx.lockedRows.size();
        tx.undoLogs.clear();
        releaseLocks(tx);
        tx.status = Status.COMMITTED;
        commits.increment();
        log.info("【AT】二阶段全局提交: xid={}, 释放 {} 把全局锁", tx.xid(), lockCount);
    }

    /**
     * 二阶段全局回滚：按 undo log 逆序还原；还原前校验后镜像，不等则计脏写。
     *
     * <p>幂等性：不幂等，事务已结束后再调用会抛异常。
     *
     * @param tx 全局事务上下文
     * @throws IllegalStateException 全局事务已结束
     */
    public void rollback(GlobalTransaction tx) {
        requireOpen(tx);
        // 先记下条数：下面清空 undoLogs 后就取不到了
        int restoreCount = tx.undoLogs.size();
        // 逆序还原：后执行的分支先撤，避免中间状态被前面的 undo 覆盖
        for (int i = tx.undoLogs.size() - 1; i >= 0; i--) {
            UndoLog undo = tx.undoLogs.get(i);
            String rowId = rowId(undo.resource(), undo.key());
            long current = rows.getOrDefault(rowId, 0L);
            if (current != undo.after()) {
                dirtyWrites.increment();
                log.warn("AT 回滚发现脏写: xid={}, row={}, expect={}, actual={}",
                        undo.xid(), rowId, undo.after(), current);
            }
            rows.put(rowId, undo.before());
            undoLogsApplied.increment();
        }
        tx.undoLogs.clear();
        releaseLocks(tx);
        tx.status = Status.ROLLED_BACK;
        rollbacks.increment();
        log.info("【AT】二阶段全局回滚: xid={}, 还原 {} 个分支, 脏写 {} 次",
                tx.xid(), restoreCount, dirtyWrites.sum());
    }

    /**
     * 协调器视角的一次完整全局事务：任一分支出错就走全局回滚。
     *
     * <p>幂等性：不幂等，每次调用都是一个新的全局事务。
     *
     * @param businessId 业务标识
     * @param branches   按顺序执行的分支列表
     * @param failAt     第几个分支失败（0 基），-1 表示全部成功
     * @return 本次全局事务的结果统计
     * @throws IllegalArgumentException failAt 越界
     */
    public AtRun run(String businessId, List<AtBranch> branches, int failAt) {
        if (failAt < -1 || failAt >= branches.size()) {
            throw new IllegalArgumentException("failAt 必须在 [-1, " + (branches.size() - 1) + "] 之间");
        }
        int conflictsBefore = (int) lockConflicts.sum();
        int dirtyBefore = (int) dirtyWrites.sum();
        int writtenBefore = (int) undoLogsWritten.sum();
        int appliedBefore = (int) undoLogsApplied.sum();
        long start = System.nanoTime();

        GlobalTransaction tx = begin(businessId);
        int executed = 0;
        try {
            for (int i = 0; i < branches.size(); i++) {
                if (i == failAt) {
                    throw new IllegalStateException("模拟第 " + (i + 1) + " 个分支失败");
                }
                branch(tx, branches.get(i));
                executed++;
            }
            commit(tx);
            return report(true, executed, writtenBefore, appliedBefore, conflictsBefore, dirtyBefore, start, null);
        } catch (RuntimeException e) {
            // 任一分支出错都走全局回滚：这是"一起成功或一起失败"的唯一入口
            log.warn("【AT】分支失败，触发全局回滚: xid={}, 已执行 {} 个分支, error={}",
                    tx.xid(), executed, e.getMessage());
            rollback(tx);
            return report(false, executed, writtenBefore, appliedBefore, conflictsBefore, dirtyBefore, start,
                    e.getMessage());
        }
    }

    /**
     * 累计统计快照，供实验接口观测锁冲突、脏写、undo log 数量。
     *
     * @return 当前累计值，扫描时不加锁，只用于展示
     */
    public AtStats stats() {
        return new AtStats(commits.sum(), rollbacks.sum(), lockConflicts.sum(), dirtyWrites.sum(),
                undoLogsWritten.sum(), undoLogsApplied.sum(), globalLocks.size());
    }

    private AtRun report(boolean committed, int executed, int writtenBefore, int appliedBefore,
                         int conflictsBefore, int dirtyBefore, long start, String failure) {
        return new AtRun(committed,
                executed,
                (int) undoLogsWritten.sum() - writtenBefore,
                (int) undoLogsApplied.sum() - appliedBefore,
                (int) lockConflicts.sum() - conflictsBefore,
                (int) dirtyWrites.sum() - dirtyBefore,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start),
                failure);
    }

    private void releaseLocks(GlobalTransaction tx) {
        for (String rowId : tx.lockedRows) {
            globalLocks.remove(rowId, tx.xid());
        }
        tx.lockedRows.clear();
    }

    private static void requireOpen(GlobalTransaction tx) {
        if (tx.status() != Status.OPEN) {
            throw new IllegalStateException("全局事务已结束: xid=" + tx.xid() + ", status=" + tx.status());
        }
    }

    private static String rowId(String resource, long key) {
        return resource + ":" + key;
    }

    /**
     * 一个全局事务的本地上下文：undo log 与持有的全局锁。
     *
     * <p>真实 Seata 里这些信息保存在 TC 服务端（xid 对应的 branch 表），
     * 这里放在对象里只是为了单机演示，所以即使进程崩溃也无法恢复。
     */
    public static final class GlobalTransaction {

        private final String xid;

        private final List<UndoLog> undoLogs = new ArrayList<>();

        private final List<String> lockedRows = new ArrayList<>();

        private Status status = Status.OPEN;

        private GlobalTransaction(String xid) {
            this.xid = xid;
        }

        public String xid() {
            return xid;
        }

        public Status status() {
            return status;
        }

        public int undoLogCount() {
            return undoLogs.size();
        }
    }

    /** 全局锁冲突：真实 Seata 里对应"获取全局锁失败"，通常重试或直接回滚。 */
    public static class AtLockConflictException extends RuntimeException {

        public AtLockConflictException(String message) {
            super(message);
        }
    }
}
