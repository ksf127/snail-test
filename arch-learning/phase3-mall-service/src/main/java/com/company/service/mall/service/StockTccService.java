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
 * <p>【解决什么问题】下单要占用库存，但用户可能几分钟后才付款、也可能永不付款。
 * 若用本地事务一直锁行，长事务会拖垮库存表；若直接扣减，用户不付款就得写一套逆向逻辑。
 * TCC 用"预占 → 确认 / 归还"把这段时间的库存变成冻结态，既不会超卖，也不会长期持锁。
 *
 * <p>【为什么这样设计】为什么库存适合 TCC 而不是 AT：
 * <ul>
 *   <li>库存是"预占"语义，业务上本来就允许先冻结再扣减，中间态是合法的</li>
 *   <li>长事务（用户可能要几分钟才付款）不应该一直持有数据库行锁</li>
 *   <li>Cancel 可以完全幂等，天然适配"协调器可能重复调用"的现实</li>
 * </ul>
 * 代价是业务要写三个方法，并自己处理空回滚、悬挂、重复调用这三类异常，
 * 这也是 TCC 比 AT 侵入性高的地方。
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
 *
 * <p>【怎么读这段代码】下单时调 {@link #tryReserve}（可多行，任一行失败即整体失败）；
 * 支付成功调 {@link #confirm} 真正扣减；取消/超时/下单失败调 {@link #cancel} 归还。
 * {@link #cancel} 可以无脑传"全部订单行"——未 Try 的行会走空回滚分支。
 * 日志按阶段打印：Try/Confirm/Cancel 每次调用都有一条 info，能直接还原库存变化。
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
     *
     * <p>幂等性：部分幂等。同一分支已 TRIED 时返回成功且不重复扣库存；
     * 但多行场景下，失败后重试会从第一行重新走，已成功行靠 TRIED 状态跳过。
     *
     * @param orderNo 订单号，参与分支 ID 的生成
     * @param lines   订单行，按顺序逐行预占
     * @return 成功，或第一个失败的商品 ID
     * @throws BizException         分支加锁超时
     * @throws IllegalStateException 分支已 Confirm 或已空回滚（悬挂）
     */
    public TryResult tryReserve(String orderNo, List<OrderLine> lines) {
        for (OrderLine line : lines) {
            if (!tryBranch(orderNo, line)) {
                log.info("【TCC-Try】失败: orderNo={}, productId={}, quantity={}；判读：商品不存在或可用库存不足（业务性失败），"
                                + "已 Try 成功的行不在这里回滚，由协调器调 Cancel",
                        orderNo, line.productId(), line.quantity());
                return TryResult.failed(line.productId());
            }
        }
        log.info("【TCC-Try】成功: orderNo={}, 预占 {} 行；判读：库存已从可用转入冻结，等待 Confirm 扣减或 Cancel 归还",
                orderNo, lines.size());
        return TryResult.ok();
    }

    /**
     * Confirm：真正扣减预占的库存。必须在 Try 成功之后调用。
     *
     * <p>幂等性：幂等。分支已 CONFIRMED 时直接跳过，不会重复扣减。
     *
     * @param orderNo 订单号
     * @param lines   订单行（通常是全部行，已确认过的会被跳过）
     * @throws BizException         分支加锁超时
     * @throws IllegalStateException 找不到 Try 记录，或分支已被 Cancel
     */
    public void confirm(String orderNo, List<OrderLine> lines) {
        for (OrderLine line : lines) {
            confirmBranch(transactionId(orderNo, line.productId()));
        }
        log.info("【TCC-Confirm】成功: orderNo={}, 确认 {} 行；判读：已 CONFIRMED 的行幂等跳过，冻结份额已真正扣减",
                orderNo, lines.size());
    }

    /**
     * Cancel：归还预占库存。对未 Try 过的分支会落空回滚记录，
     * 因此可以安全地对"全部订单行"调用，而不用关心哪几行 Try 成功了。
     *
     * <p>幂等性：幂等。已 CANCELLED 的分支直接跳过；未 Try 的分支落空回滚记录后返回。
     *
     * @param orderNo 订单号
     * @param lines   订单行
     * @throws BizException         分支加锁超时
     * @throws IllegalStateException 分支已 Confirm（此时不该再 Cancel）
     */
    public void cancel(String orderNo, List<OrderLine> lines) {
        for (OrderLine line : lines) {
            cancelBranch(transactionId(orderNo, line.productId()), line);
        }
        log.info("【TCC-Cancel】完成: orderNo={}, 归还 {} 行；判读：未 Try 的行落空回滚记录，已 CANCELLED 的行幂等跳过",
                orderNo, lines.size());
    }

    /**
     * TCC 分支统计快照，全部来自仓储计数与内存累加器。
     *
     * <p>幂等：只读，可重复调用。
     *
     * @return 分支状态分布与各类异常计数
     */
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

    /**
     * Try 单分支：锁内查分支状态 → 校验是否允许 → 预占库存 → 落 TRIED 记录。
     *
     * <p>锁必须在查询之前拿：先查再锁会让两个并发的 Try 都读到"无记录"，
     * 然后各自预占一次，库存被扣两倍。
     *
     * @return false 表示商品不存在或可用库存不足（业务性失败，不算异常）
     * @throws IllegalStateException 分支已 Confirm 或已空回滚（悬挂）
     */
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
                    // 重复 Try：库存已经预占过，直接返回成功，不能再扣一次
                    idempotentRepeats.increment();
                    log.debug("【TCC-Try】重复调用，幂等返回成功: txId={}；原因=分支已是 TRIED，再预占会扣两次库存",
                            txId);
                    return true;
                }
                if (tx.status() == TccTransaction.Status.CONFIRMED) {
                    log.debug("【TCC-Try】拒绝: txId={}；原因=分支已 CONFIRMED，Try 晚于 Confirm 属协调器乱序",
                            txId);
                    throw new IllegalStateException("分支已 Confirm，重复 Try: " + txId);
                }
                // 空回滚记录已存在：这次 Try 对应的资源没有 Cancel 会来回收，直接拒绝悬挂
                suspendedRejections.increment();
                log.warn("【TCC-Try】拒绝悬挂: txId={}；原因=已存在空回滚记录（Cancel 先到），"
                        + "若允许本次 Try 占住库存，不会再有 Cancel 来回收，库存会被永久冻结", txId);
                throw new IllegalStateException("分支已空回滚，拒绝悬挂的 Try: " + txId);
            }
            Stock stock = stocks.findById(line.productId()).orElse(null);
            if (stock == null) {
                log.debug("【TCC-Try】失败: txId={}, productId={}；原因=商品/库存记录不存在",
                        txId, line.productId());
                return false;
            }
            if (!stock.reserve(line.quantity())) {
                log.debug("【TCC-Try】失败: txId={}, quantity={}；原因=可用库存不足，明细见库存预占日志",
                        txId, line.quantity());
                return false;
            }
            transactions.save(new TccTransaction(txId, line.productId(), line.quantity(), Instant.now()));
            tried.increment();
            log.debug("【TCC-Try】分支预占完成: txId={}, productId={}, quantity={}；库存已从可用转入冻结",
                    txId, line.productId(), line.quantity());
            return true;
        }
    }

    /**
     * Confirm 单分支：锁内确认状态 → 扣减冻结库存 → 迁移到 CONFIRMED。
     *
     * <p>幂等：已 CONFIRMED 直接返回；已 CANCELLED 抛异常（协调器乱序）。
     */
    private void confirmBranch(String txId) {
        LockHandle handle = lock.lock("tcc:" + txId,
                properties.getOrder().getLockWait(),
                properties.getOrder().getLockLease());
        if (handle == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "TCC 分支加锁超时: " + txId);
        }
        try (LockHandle ignored = handle) {
            Optional<TccTransaction> existing = transactions.findByTxId(txId);
            if (existing.isEmpty()) {
                log.warn("【TCC-Confirm】失败: txId={}；原因=找不到 Try 记录，Confirm 先于 Try 到达", txId);
                throw new IllegalStateException("Confirm 找不到 Try 记录: " + txId);
            }
            TccTransaction tx = existing.get();
            if (tx.status() == TccTransaction.Status.CONFIRMED) {
                // 重复 Confirm：冻结库存已经扣过了，直接幂等返回
                idempotentRepeats.increment();
                log.debug("【TCC-Confirm】重复调用，幂等返回: txId={}；原因=分支已是 CONFIRMED，冻结库存已扣过",
                        txId);
                return;
            }
            if (tx.status() == TccTransaction.Status.CANCELLED) {
                log.warn("【TCC-Confirm】拒绝: txId={}；原因=分支已 CANCELLED，Confirm 晚于 Cancel 属协调器乱序",
                        txId);
                throw new IllegalStateException("分支已 Cancel，收到 Confirm: " + txId);
            }
            Stock stock = stocks.findById(tx.productId())
                    .orElseThrow(() -> new IllegalStateException("库存记录丢失: productId=" + tx.productId()));
            stock.confirmReserved(tx.quantity());
            tx.markConfirmed();
            transactions.save(tx);
            confirmed.increment();
            log.debug("【TCC-Confirm】分支完成: txId={}, quantity={}；冻结份额已真正扣减",
                    txId, tx.quantity());
        }
    }

    /**
     * Cancel 单分支：锁内判断是空回滚、重复调用还是正常归还。
     *
     * <p>幂等：已 CANCELLED 直接返回；无记录时落空回滚记录（防止后续悬挂的 Try 占住库存）。
     */
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
                log.info("【TCC-Cancel】空回滚，已留痕: txId={}, productId={}, quantity={}；"
                                + "原因=Cancel 先于 Try 到达，先落 CANCELLED 记录，后续补来的 Try 会被判为悬挂",
                        txId, line.productId(), line.quantity());
                return;
            }
            TccTransaction tx = existing.get();
            if (tx.status() == TccTransaction.Status.CANCELLED) {
                // 重复 Cancel：库存已经归还过，不能再还一次（否则库存越还越多）
                idempotentRepeats.increment();
                log.debug("【TCC-Cancel】重复调用，幂等返回: txId={}；原因=分支已是 CANCELLED，再还会把库存越还越多",
                        txId);
                return;
            }
            if (tx.status() == TccTransaction.Status.CONFIRMED) {
                // Confirm 之后再 Cancel 是协调器乱序，不能静默处理
                log.error("【TCC-Cancel】晚于 Confirm，数据不一致: txId={}；原因=分支已 CONFIRMED，"
                        + "此时归还等于把已售库存凭空加回可用，直接拒绝", txId);
                throw new IllegalStateException("分支已 Confirm，收到 Cancel: " + txId);
            }
            Stock stock = stocks.findById(tx.productId())
                    .orElseThrow(() -> new IllegalStateException("库存记录丢失: productId=" + tx.productId()));
            stock.releaseReserved(tx.quantity());
            tx.markCancelled();
            transactions.save(tx);
            cancelled.increment();
            log.debug("【TCC-Cancel】分支完成: txId={}, quantity={}；冻结份额已退回可用",
                    txId, tx.quantity());
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
