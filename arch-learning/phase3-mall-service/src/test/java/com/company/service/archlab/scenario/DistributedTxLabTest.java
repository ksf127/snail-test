package com.company.service.archlab.scenario;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.company.service.archlab.scenario.distributedtx.AtTransactionManager;
import com.company.service.archlab.scenario.distributedtx.DistributedTxLab;
import com.company.service.archlab.scenario.distributedtx.SagaOrchestrator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DistributedTxLabTest {

    private final DistributedTxLab lab = new DistributedTxLab();

    @Test
    void atCommitAppliesAllBranches() {
        DistributedTxLab.Result result = lab.run(DistributedTxLab.Mechanism.AT, -1);

        assertThat(result.completed()).isTrue();
        assertThat(result.consistent()).isTrue();
        assertThat(result.stockAvailable()).isEqualTo(DistributedTxLab.INITIAL_STOCK - DistributedTxLab.QTY);
        assertThat(result.balanceAvailable())
                .isEqualTo(DistributedTxLab.INITIAL_BALANCE - DistributedTxLab.AMOUNT);
        assertThat(result.orderPresent()).isTrue();
        assertThat(result.undoLogsWritten()).isEqualTo(3);
        assertThat(result.undoLogsApplied()).isZero();
        assertThat(result.lockConflicts()).isZero();
    }

    @Test
    void atRollbackRestoresAllBranches() {
        DistributedTxLab.Result result = lab.run(DistributedTxLab.Mechanism.AT, 2);

        assertThat(result.completed()).isFalse();
        assertThat(result.consistent()).isTrue();
        assertThat(result.stockAvailable()).isEqualTo(DistributedTxLab.INITIAL_STOCK);
        assertThat(result.balanceAvailable()).isEqualTo(DistributedTxLab.INITIAL_BALANCE);
        assertThat(result.orderPresent()).isFalse();
        // 前两个分支已经写了 undo log，回滚时逐个还原
        assertThat(result.undoLogsWritten()).isEqualTo(2);
        assertThat(result.undoLogsApplied()).isEqualTo(2);
        assertThat(result.compensations()).isEqualTo(2);
    }

    @Test
    void atFirstBranchFailureWritesNoUndoLog() {
        DistributedTxLab.Result result = lab.run(DistributedTxLab.Mechanism.AT, 0);

        assertThat(result.completed()).isFalse();
        assertThat(result.undoLogsWritten()).isZero();
        assertThat(result.undoLogsApplied()).isZero();
        assertThat(result.consistent()).isTrue();
    }

    @Test
    void tccCommitClearsFrozenShares() {
        DistributedTxLab.Result result = lab.run(DistributedTxLab.Mechanism.TCC, -1);

        assertThat(result.completed()).isTrue();
        assertThat(result.consistent()).isTrue();
        assertThat(result.stockFrozen()).isZero();
        assertThat(result.balanceFrozen()).isZero();
        assertThat(result.stockAvailable()).isEqualTo(DistributedTxLab.INITIAL_STOCK - DistributedTxLab.QTY);
        assertThat(result.compensations()).isZero();
    }

    @Test
    void tccPartialFailureCancelsTriedBranchesInReverse() {
        DistributedTxLab.Result result = lab.run(DistributedTxLab.Mechanism.TCC, 2);

        assertThat(result.completed()).isFalse();
        assertThat(result.consistent()).isTrue();
        assertThat(result.branches()).isEqualTo(2);
        assertThat(result.compensations()).isEqualTo(2);
        assertThat(result.stockFrozen()).isZero();
        assertThat(result.balanceFrozen()).isZero();
        assertThat(result.stockAvailable()).isEqualTo(DistributedTxLab.INITIAL_STOCK);
        assertThat(result.orderPresent()).isFalse();
    }

    @Test
    void sagaCommitRunsAllStepsWithoutCompensation() {
        DistributedTxLab.Result result = lab.run(DistributedTxLab.Mechanism.SAGA, -1);

        assertThat(result.completed()).isTrue();
        assertThat(result.consistent()).isTrue();
        assertThat(result.branches()).isEqualTo(3);
        assertThat(result.compensations()).isZero();
        assertThat(result.stockAvailable()).isEqualTo(DistributedTxLab.INITIAL_STOCK - DistributedTxLab.QTY);
    }

    @Test
    void sagaPartialFailureCompensatesExecutedSteps() {
        DistributedTxLab.Result result = lab.run(DistributedTxLab.Mechanism.SAGA, 2);

        assertThat(result.completed()).isFalse();
        assertThat(result.consistent()).isTrue();
        assertThat(result.branches()).isEqualTo(2);
        assertThat(result.compensations()).isEqualTo(2);
        assertThat(result.stockAvailable()).isEqualTo(DistributedTxLab.INITIAL_STOCK);
        assertThat(result.balanceAvailable()).isEqualTo(DistributedTxLab.INITIAL_BALANCE);
        assertThat(result.orderPresent()).isFalse();
    }

    @Test
    void threeMechanismsConvergeToSameFinalState() {
        for (int failAt = -1; failAt < 3; failAt++) {
            List<DistributedTxLab.Result> results = lab.compare(failAt);

            assertThat(results).hasSize(3);
            assertThat(results).allMatch(DistributedTxLab.Result::consistent);
            boolean expectCompleted = failAt < 0;
            assertThat(results).allMatch(result -> result.completed() == expectCompleted);
            DistributedTxLab.Result first = results.get(0);
            for (DistributedTxLab.Result result : results) {
                assertThat(result.stockAvailable()).isEqualTo(first.stockAvailable());
                assertThat(result.balanceAvailable()).isEqualTo(first.balanceAvailable());
                assertThat(result.orderPresent()).isEqualTo(first.orderPresent());
            }
        }
    }

    @Test
    void atGlobalLockBlocksConcurrentTransactionOnSameRow() {
        AtTransactionManager manager = new AtTransactionManager();
        manager.seed("stock", 1L, 100);
        AtTransactionManager.AtBranch deduct = new AtTransactionManager.AtBranch("stock", 1L, -3);

        AtTransactionManager.GlobalTransaction first = manager.begin("t1");
        manager.branch(first, deduct);

        AtTransactionManager.GlobalTransaction second = manager.begin("t2");
        assertThatThrownBy(() -> manager.branch(second, deduct))
                .isInstanceOf(AtTransactionManager.AtLockConflictException.class)
                .hasMessageContaining("已被全局事务");
        assertThat(manager.stats().lockConflicts()).isEqualTo(1);
        assertThat(manager.stats().activeLocks()).isEqualTo(1);

        manager.rollback(first);
        assertThat(manager.stats().activeLocks()).isZero();
        // 锁释放后第二个全局事务才能写同一行；第一个事务已回滚，所以是 100 - 3
        manager.branch(second, deduct);
        manager.commit(second);
        assertThat(manager.valueOf("stock", 1L)).isEqualTo(97);
    }

    @Test
    void atRollbackDetectsDirtyWrite() {
        AtTransactionManager manager = new AtTransactionManager();
        manager.seed("balance", 42L, 10_000);
        AtTransactionManager.GlobalTransaction tx = manager.begin("dirty");
        manager.branch(tx, new AtTransactionManager.AtBranch("balance", 42L, -1_500));

        // 绕过 Seata 代理改写同一行：后镜像已对不上
        manager.localWrite("balance", 42L, 50);
        manager.rollback(tx);

        assertThat(manager.stats().dirtyWrites()).isEqualTo(1);
        assertThat(manager.valueOf("balance", 42L)).isEqualTo(10_000);
    }

    @Test
    void sagaRetriesCompensationUntilSuccess() {
        SagaOrchestrator orchestrator = new SagaOrchestrator(3);
        AtomicInteger compensationCalls = new AtomicInteger();
        List<SagaOrchestrator.SagaStep> steps = List.of(
                new SagaOrchestrator.SagaStep("first",
                        () -> {
                        },
                        () -> {
                            if (compensationCalls.incrementAndGet() < 2) {
                                throw new IllegalStateException("补偿暂时失败");
                            }
                        }),
                new SagaOrchestrator.SagaStep("second", () -> {
                }, () -> {
                }));

        SagaOrchestrator.SagaRun run = orchestrator.run("retry", steps, 1);

        assertThat(run.completed()).isFalse();
        assertThat(run.compensated()).containsExactly("first");
        assertThat(run.failedCompensations()).isEmpty();
        assertThat(run.compensationRetries()).isEqualTo(1);
    }

    @Test
    void sagaRecordsCompensationThatNeverSucceeds() {
        SagaOrchestrator orchestrator = new SagaOrchestrator(2);
        List<SagaOrchestrator.SagaStep> steps = List.of(
                new SagaOrchestrator.SagaStep("broken",
                        () -> {
                        },
                        () -> {
                            throw new IllegalStateException("补偿一直失败");
                        }),
                new SagaOrchestrator.SagaStep("second", () -> {
                }, () -> {
                }));

        SagaOrchestrator.SagaRun run = orchestrator.run("dead", steps, 1);

        assertThat(run.completed()).isFalse();
        assertThat(run.compensated()).isEmpty();
        assertThat(run.failedCompensations()).containsExactly("broken");
        // 最多尝试 2 次，重试 1 次
        assertThat(run.compensationRetries()).isEqualTo(1);
    }

    @Test
    void shouldRejectOutOfRangeArguments() {
        assertThatThrownBy(() -> lab.run(null, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mechanism");
        assertThatThrownBy(() -> lab.run(DistributedTxLab.Mechanism.AT, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failAt");
        assertThatThrownBy(() -> lab.run(DistributedTxLab.Mechanism.AT, -2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failAt");
        assertThatThrownBy(() -> lab.compare(3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failAt");
        assertThatThrownBy(() -> new SagaOrchestrator(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCompensationAttempts");
    }
}
