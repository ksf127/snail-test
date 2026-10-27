package com.company.service.mall.service;

import com.company.service.mall.domain.OrderLine;
import com.company.service.mall.domain.Stock;
import com.company.service.mall.domain.TccTransaction;
import com.company.service.mall.infra.InMemoryStockRepository;
import com.company.service.mall.infra.InMemoryTccTransactionRepository;
import com.company.service.mall.support.MallProperties;
import com.company.starter.lock.DistributedLock;
import com.company.starter.lock.InMemoryDistributedLock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCC 三个分支 + 三个异常场景（空回滚 / 悬挂 / 幂等）的行为约束。
 */
class StockTccServiceTest {

    private InMemoryStockRepository stocks;

    private InMemoryTccTransactionRepository transactions;

    private StockTccService service;

    @BeforeEach
    void setUp() {
        MallProperties properties = new MallProperties();
        DistributedLock lock = new InMemoryDistributedLock("test:");
        stocks = new InMemoryStockRepository();
        transactions = new InMemoryTccTransactionRepository();
        service = new StockTccService(stocks, transactions, lock, properties);
    }

    @Test
    @DisplayName("Try 预占、Confirm 扣减预占量、重复 Confirm 幂等")
    void tryThenConfirm() {
        stocks.save(new Stock(1L, 10));
        List<OrderLine> lines = List.of(new OrderLine(1L, 3, 100L));

        assertThat(service.tryReserve("O1", lines).success()).isTrue();
        assertThat(stocks.findById(1L).orElseThrow().available()).isEqualTo(7);
        assertThat(stocks.findById(1L).orElseThrow().reserved()).isEqualTo(3);
        assertThat(transactions.findByTxId("O1:1").orElseThrow().status())
                .isEqualTo(TccTransaction.Status.TRIED);

        service.confirm("O1", lines);
        // Confirm 只扣减预占量，available 已经是 Try 后的值，不能再变
        assertThat(stocks.findById(1L).orElseThrow().available()).isEqualTo(7);
        assertThat(stocks.findById(1L).orElseThrow().reserved()).isZero();
        assertThat(transactions.findByTxId("O1:1").orElseThrow().status())
                .isEqualTo(TccTransaction.Status.CONFIRMED);

        service.confirm("O1", lines);
        assertThat(stocks.findById(1L).orElseThrow().reserved()).isZero();
        assertThat(service.stats().idempotentRepeats()).isEqualTo(1);
    }

    @Test
    @DisplayName("Cancel 归还库存，重复 Cancel 幂等")
    void cancelAfterTry() {
        stocks.save(new Stock(1L, 10));
        List<OrderLine> lines = List.of(new OrderLine(1L, 3, 100L));

        service.tryReserve("O1", lines);
        service.cancel("O1", lines);

        assertThat(stocks.findById(1L).orElseThrow().available()).isEqualTo(10);
        assertThat(stocks.findById(1L).orElseThrow().reserved()).isZero();
        assertThat(transactions.findByTxId("O1:1").orElseThrow().status())
                .isEqualTo(TccTransaction.Status.CANCELLED);

        service.cancel("O1", lines);
        assertThat(stocks.findById(1L).orElseThrow().available()).isEqualTo(10);
        assertThat(service.stats().idempotentRepeats()).isEqualTo(1);
    }

    @Test
    @DisplayName("空回滚留痕，随后补来的 Try 被当作悬挂拒绝，库存不被占用")
    void emptyRollbackThenSuspendedTry() {
        stocks.save(new Stock(1L, 10));
        List<OrderLine> lines = List.of(new OrderLine(1L, 3, 100L));

        // Cancel 先到：必须落一条 CANCELLED，否则后面的 Try 没有 Cancel 会来回收
        service.cancel("O1", lines);
        assertThat(service.stats().emptyRollbacks()).isEqualTo(1);
        assertThat(transactions.findByTxId("O1:1").orElseThrow().status())
                .isEqualTo(TccTransaction.Status.CANCELLED);

        assertThatThrownBy(() -> service.tryReserve("O1", lines))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("悬挂");
        assertThat(service.stats().suspendedRejections()).isEqualTo(1);
        assertThat(stocks.findById(1L).orElseThrow().available()).isEqualTo(10);
        assertThat(stocks.findById(1L).orElseThrow().reserved()).isZero();
    }

    @Test
    @DisplayName("库存不足时 Try 失败，且不产生分支记录")
    void tryFailsWhenStockInsufficient() {
        stocks.save(new Stock(1L, 2));

        StockTccService.TryResult result = service.tryReserve("O1", List.of(new OrderLine(1L, 3, 100L)));

        assertThat(result.success()).isFalse();
        assertThat(result.failedProductId()).isEqualTo(1L);
        assertThat(stocks.findById(1L).orElseThrow().available()).isEqualTo(2);
        assertThat(transactions.count()).isZero();
    }

    @Test
    @DisplayName("多分支部分失败：协调器对全部订单行 Cancel，成功的分支归还、失败的分支空回滚")
    void partialFailureThenCancelAllBranches() {
        stocks.save(new Stock(1L, 10));
        stocks.save(new Stock(2L, 1));
        List<OrderLine> lines = List.of(
                new OrderLine(1L, 2, 100L),
                new OrderLine(2L, 2, 100L));

        StockTccService.TryResult result = service.tryReserve("O1", lines);

        assertThat(result.success()).isFalse();
        assertThat(result.failedProductId()).isEqualTo(2L);
        assertThat(stocks.findById(1L).orElseThrow().available()).isEqualTo(8);

        service.cancel("O1", lines);

        assertThat(stocks.findById(1L).orElseThrow().available()).isEqualTo(10);
        assertThat(stocks.findById(1L).orElseThrow().reserved()).isZero();
        assertThat(stocks.findById(2L).orElseThrow().available()).isEqualTo(1);
        // CANCELLED 记录 = 真实归还的 p1 + 空回滚留痕的 p2，共 2 条
        assertThat(service.stats().cancelledBranches()).isEqualTo(2);
        assertThat(service.stats().cancelSuccess()).isEqualTo(1);
        assertThat(service.stats().emptyRollbacks()).isEqualTo(1);
    }
}
