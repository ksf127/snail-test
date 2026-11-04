package com.company.service.mall.infra;

import com.company.service.mall.domain.OutboxMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 本地消息表仓储：写入 + 去重查询 + 到期扫描。
 *
 * <p>【解决什么问题】业务事务要能把"待发消息"和业务数据一起提交；中继要能不断取走
 * "待发且已到期"的消息，失败后按退避时间重新进入待发队列。
 *
 * <p>【为什么这样设计】把"扫描"定义成 {@code status=PENDING AND next_attempt_at<=now}，
 * 而不是维护内存队列：进程重启后未发出的消息仍然存在，这是 outbox 相比
 * "先发 MQ 再写库"最大的优势。{@link #findDue} 的语义必须允许并发调用，
 * 真实实现靠 {@code FOR UPDATE SKIP LOCKED} 让多个中继实例互不阻塞。
 *
 * <p>【怎么读这段代码】{@link #save} 是 upsert；{@link #findDue} 只读，返回的是可变实体，
 * 中继拿到后会直接更新其状态并再次 save。
 */
public interface OutboxMessageRepository {

    /**
     * 保存消息。幂等：同一 id 覆盖。
     */
    void save(OutboxMessage message);

    Optional<OutboxMessage> findById(long id);

    /**
     * 业务事件去重：同一个 dedupeKey 只允许存在一条消息。
     *
     * <p>幂等：只读，可重复调用。
     */
    Optional<OutboxMessage> findByDedupeKey(String dedupeKey);

    /**
     * 中继扫描：PENDING 且 nextAttemptAt 已到期的消息，按到期时间升序。
     * 真实实现就是 {@code SELECT ... WHERE status='PENDING' AND next_attempt_at <= ? ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED}，
     * SKIP LOCKED 让多个中继实例可以并行拉取而不会互相阻塞。
     */
    List<OutboxMessage> findDue(Instant now, int limit);

    List<OutboxMessage> findAll();

    long countByStatus(OutboxMessage.Status status);

    long count();
}
