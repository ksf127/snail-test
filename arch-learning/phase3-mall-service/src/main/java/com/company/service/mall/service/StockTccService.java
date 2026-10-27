package com.company.service.mall.service;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.service.mall.domain.OrderLine;
import com.company.service.mall.domain.Stock;
import com.company.service.mall.domain.TccTransaction;
import com.company.service.mall.infra.StockRepository;
import com.company.service.mall.infra.TccTransactionRepository;
import com.company.service.mall.support.MallProperties;
import com.company.starter.lock.DistributedLock;
import com.company.starter.lock.LockHandle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * 库存 TCC 的 Try / Confirm / Cancel 三个分支。
 *
 * <p>为什么库存适合 TCC 而不是 AT：
 * <ul>
 *   <li>库存是"预占"语义，业务上本来就允许先冻结再扣减，中间态是合法的</li>
 *   <li>长事务（用户可能要几分钟才付款）不应该一直持有数据库行锁</li>
 *   <li>Cancel 可以完全幂等，天然适配"协调器可能重复调用"的现实</li>
 * </ul>
 *
 * <p>三个必须处理的异常场景，都在这里显式落代码：
 * <pre>
 *   空回滚：Cancel 先到、Try 未到  → 落 CANCELLED 记录
 *   悬挂  ：Try 后到、发现已 CANCELLED → 直接拒绝，不能占库存
 *   幂等  ：Confirm/Cancel 重复到达 → 查状态后直接返回
 * </pre>
 *
 * <p>锁的粒度是 {@code tcc:{orderNo}:{productId}}：同一个分支的并发调用互斥，
 * 不同商品之间不互相阻塞。
 */
@Service
public class StockTccService {

    private static final Logger log = LoggerFactory.getLogger(StockTccService.class);

    private final StockRepository stocks;

    private final TccTransactionRepository transactions;

    private final DistributedLock lock;

    private final MallProperties properties;

    private final LongAdder tried = new LongAdder();

    private final LongAdder confirmed = new LongAdder();

    private final LongAdder cancelled = new LongAdder();

    private final LongAdder emptyRollbacks = new LongAdder();

    private final LongAdder suspendedRejections = new LongAdder();

    private final LongAdder idempotentRepeats = new LongAdder();

    public StockTccService(StockRepository stocks,
                           TccTransactionRepository transactions,
                           DistributedLock lock,
                           MallProperties properties) {
        this.stocks = stocks;
        this.transactions = transactions;
        this.lock = lock;
        this.properties = properties;
    }

    /**
     * Try：逐行预占库存。任一行失败立即返回失败，由协调器（OrderService）决定回滚。
     *
     * <p>这里不自动回滚已成功的行，是为了让"协调器负责全局回滚"这条职责边界清晰；
     * 代价是调用方必须记得在失败时调用 {@link #cancel}。真实 TCC 框架里这一步由 TC 完成。
     */
    public TryResult tryReserve(String orderNo, List<OrderLine> lines) {
        for (OrderLine line : lines) {
            if (!tryBranch(orderNo, line)) {
                log.debug("TCC Try 失败: orderNo={}, productId={}", orderNo, line.productId());
                return TryResult.failed(line.productId());
            }
        }
        return TryResult.ok();
    }

    /**
     * Confirm：真正扣减预占的库存。必须在 Try 成功之后调用。
     */
    public void confirm(String orderNo, List<OrderLine> lines) {
        for (OrderLine line : lines) {
            confirmBranch(transactionId(orderNo, line.productId()));
        }
    }

    /**
     * Cancel：归还预占库存。对未 Try 过的分支会落空回滚记录，
     * 因此可以安全地对"全部订单行"调用，而不用关心哪几行 Try 成功了。
     */
    public void cancel(String orderNo, List<OrderLine> lines) {
        for (OrderLine line : lines) {
            cancelBranch(transactionId(orderNo, line.productId()), line);
        }
    }

    public TccStats stats() {
        return new TccStats(
                transactions.countByStatus(TccTransaction.Status.TRIED),
                transactions.countByStatus(TccTransaction.Status.CONFIRMED),
                transactions.countByStatus(TccTransaction.Status.CANCELLED),
                emptyRollbacks.sum(),
                suspendedRejections.sum(),
                idempotentRepeats.sum(),
                tried.sum(),
                confirmed.sum(),
                cancelled.sum());
    }

    private boolean tryBranch(String orderNo, OrderLine line) {
        String txId = transactionId(orderNo, line.productId());
        LockHandle handle = lock.lock("tcc:" + txId,
                properties.getOrder().getLockWait(),
                properties.getOrder().getLockLease());
        if (handle == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "TCC 分支加锁超时: " + txId);
        }
        try (LockHandle ignored = handle) {
            Optional<TccTransaction> existing = transactions.findByTxId(txId);
            if (existing.isPresent()) {
                TccTransaction tx = existing.get();
                if (tx.status() == TccTransaction.Status.TRIED) {
                    idempotentRepeats.increment();
                    return true;
                }
                if (tx.status() == TccTransaction.Status.CONFIRMED) {
                    throw new IllegalStateException("分支已 Confirm，重复 Try: " + txId);
                }
                // 空回滚记录已存在：这次 Try 对应的资源没有 Cancel 会来回收，直接拒绝悬挂
                suspendedRejections.increment();
                throw new IllegalStateException("分支已空回滚，拒绝悬挂的 Try: " + txId);
            }
            Stock stock = stocks.findById(line.productId()).orElse(null);
            if (stock == null) {
                return false;
            }
            if (!stock.reserve(line.quantity())) {
                return false;
            }
            transactions.save(new TccTransaction(txId, line.productId(), line.quantity(), Instant.now()));
            tried.increment();
            return true;
        }
    }

    private void confirmBranch(String txId) {
        LockHandle handle = lock.lock("tcc:" + txId,
                properties.getOrder().getLockWait(),
                properties.getOrder().getLockLease());
        if (handle == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "TCC 分支加锁超时: " + txId);
        }
        try (LockHandle ignored = handle) {
            TccTransaction tx = transactions.findByTxId(txId)
                    .orElseThrow(() -> new IllegalStateException("Confirm 找不到 Try 记录: " + txId));
            if (tx.status() == TccTransaction.Status.CONFIRMED) {
                idempotentRepeats.increment();
                return;
            }
            if (tx.status() == TccTransaction.Status.CANCELLED) {
                throw new IllegalStateException("分支已 Cancel，收到 Confirm: " + txId);
            }
            Stock stock = stocks.findById(tx.productId())
                    .orElseThrow(() -> new IllegalStateException("库存记录丢失: productId=" + tx.productId()));
            stock.confirmReserved(tx.quantity());
            tx.markConfirmed();
            transactions.save(tx);
            confirmed.increment();
        }
    }

    private void cancelBranch(String txId, OrderLine line) {
        LockHandle handle = lock.lock("tcc:" + txId,
                properties.getOrder().getLockWait(),
                properties.getOrder().getLockLease());
        if (handle == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "TCC 分支加锁超时: " + txId);
        }
        try (LockHandle ignored = handle) {
            Optional<TccTransaction> existing = transactions.findByTxId(txId);
            if (existing.isEmpty()) {
                // 空回滚：Cancel 先于 Try 到达。必须留痕，否则稍后补来的 Try 会永久占住库存
                transactions.save(TccTransaction.emptyRollback(txId, line.productId(), line.quantity(), Instant.now()));
                emptyRollbacks.increment();
                log.debug("TCC 空回滚: txId={}", txId);
                return;
            }
            TccTransaction tx = existing.get();
            if (tx.status() == TccTransaction.Status.CANCELLED) {
                idempotentRepeats.increment();
                return;
            }
            if (tx.status() == TccTransaction.Status.CONFIRMED) {
                throw new IllegalStateException("分支已 Confirm，收到 Cancel: " + txId);
            }
            Stock stock = stocks.findById(tx.productId())
                    .orElseThrow(() -> new IllegalStateException("库存记录丢失: productId=" + tx.productId()));
            stock.releaseReserved(tx.quantity());
            tx.markCancelled();
            transactions.save(tx);
            cancelled.increment();
        }
    }

    /**
     * 分支 ID 由订单号与商品 ID 组成，天然幂等：同一个订单对同一个商品只可能有一个分支。
     */
    private String transactionId(String orderNo, long productId) {
        return orderNo + ":" + productId;
    }

    public record TryResult(boolean success, long failedProductId) {

        static TryResult ok() {
            return new TryResult(true, 0L);
        }

        static TryResult failed(long productId) {
            return new TryResult(false, productId);
        }
    }

    public record TccStats(long triedBranches,
                           long confirmedBranches,
                           long cancelledBranches,
                           long emptyRollbacks,
                           long suspendedRejections,
                           long idempotentRepeats,
                           long trySuccess,
                           long confirmSuccess,
                           long cancelSuccess) {
    }
}
