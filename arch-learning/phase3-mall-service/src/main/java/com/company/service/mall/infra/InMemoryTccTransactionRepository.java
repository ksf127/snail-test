package com.company.service.mall.infra;

import com.company.service.mall.domain.TccTransaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * TCC 分支事务记录的内存实现，是"幂等 / 空回滚 / 防悬挂"得以成立的前提。
 *
 * <p>【解决什么问题】协调器的 Confirm/Cancel 可能重复、乱序、甚至先于 Try 到达。
 * 只有把每次分支的状态落库（txId 唯一），才能判断这次调用到底该不该动库存。
 *
 * <p>【为什么这样设计】以 {@code txId = orderNo:productId} 作主键：
 * 一个订单里同一个商品只有一条分支记录，天然唯一。
 * 换成数据库时这里必须加 {@code SELECT ... FOR UPDATE} 或唯一索引，
 * 因为"先查再判断"在并发下会两个 Confirm 同时通过 —— 内存里靠的是
 * {@link TccTransaction} 自身的 synchronized 状态迁移。
 *
 * <p>【怎么读这段代码】仓储只做存取，不做状态判断；状态机全部在
 * {@link TccTransaction#markConfirmed()} / {@link TccTransaction#markCancelled()} 里。
 * 日志一律 debug —— 内存实现每个操作都打 info 会刷屏。
 */
@Repository
public class InMemoryTccTransactionRepository implements TccTransactionRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTccTransactionRepository.class);

    private final ConcurrentMap<String, TccTransaction> transactions = new ConcurrentHashMap<>();

    /**
     * 保存分支记录。幂等：同一 txId 覆盖。
     */
    @Override
    public void save(TccTransaction transaction) {
        transactions.put(transaction.txId(), transaction);
        log.debug("保存 TCC 分支记录: txId={}, status={}", transaction.txId(), transaction.status());
    }

    @Override
    public Optional<TccTransaction> findByTxId(String txId) {
        return Optional.ofNullable(transactions.get(txId));
    }

    @Override
    public long countByStatus(TccTransaction.Status status) {
        return transactions.values().stream().filter(tx -> tx.status() == status).count();
    }

    @Override
    public long count() {
        return transactions.size();
    }
}
