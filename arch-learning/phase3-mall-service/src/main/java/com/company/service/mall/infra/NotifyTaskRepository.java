package com.company.service.mall.infra;

import com.company.service.mall.domain.NotifyTask;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 通知任务仓储：按支付流水号去重 + 到期扫描。
 *
 * <p>【解决什么问题】支付成功后要通知外部商户，而外部系统可能长时间不可用。
 * 任务必须持久化，才能在中继重启后继续重试，而不是把通知丢在内存里。
 *
 * <p>【为什么这样设计】以 paymentNo 作唯一键，天然保证"一笔支付一条通知"；
 * 扫描条件与 outbox 相同（PENDING + 到期），但语义更弱：
 * 重试到上限就进死信，不做无限重试，因为对方系统不可控。
 *
 * <p>【怎么读这段代码】{@link #save} upsert；{@link #findDue} 只读；
 * 统计方法只用于观测积压量。
 */
public interface NotifyTaskRepository {

    /**
     * 保存通知任务。幂等：同一 paymentNo 覆盖。
     */
    void save(NotifyTask task);

    Optional<NotifyTask> findByPaymentNo(String paymentNo);

    /**
     * 扫描已到期的 PENDING 任务，按到期时间升序取前 {@code limit} 条。
     *
     * <p>幂等：只读，可重复调用。
     */
    List<NotifyTask> findDue(Instant now, int limit);

    long countByStatus(NotifyTask.Status status);

    long count();
}
