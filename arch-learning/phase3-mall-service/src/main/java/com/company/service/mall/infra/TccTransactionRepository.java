package com.company.service.mall.infra;

import com.company.service.mall.domain.TccTransaction;

import java.util.Optional;

/**
 * TCC 分支记录仓储。
 *
 * <p>【解决什么问题】Confirm/Cancel 可能重复、乱序、先于 Try 到达，
 * 必须持久化每个分支的状态，才能做到幂等、空回滚和防悬挂。
 *
 * <p>【为什么这样设计】以 {@code txId = orderNo:productId} 为唯一键：同一订单同一商品只有一条分支。
 * 状态判断放在实体内部（synchronized 迁移），仓储只负责存取；
 * 落库实现要保证 {@code findByTxId + save} 的原子性，否则并发 Confirm 会双双通过。
 *
 * <p>【怎么读这段代码】{@link #save} upsert；{@link #findByTxId} 是协调器判定阶段的唯一依据。
 */
public interface TccTransactionRepository {

    /**
     * 保存分支记录。幂等：同一 txId 覆盖。
     */
    void save(TccTransaction transaction);

    Optional<TccTransaction> findByTxId(String txId);

    long countByStatus(TccTransaction.Status status);

    long count();
}
