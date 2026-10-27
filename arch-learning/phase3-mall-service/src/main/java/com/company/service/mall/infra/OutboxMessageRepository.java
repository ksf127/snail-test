package com.company.service.mall.infra;

import com.company.service.mall.domain.OutboxMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxMessageRepository {

    void save(OutboxMessage message);

    Optional<OutboxMessage> findById(long id);

    /**
     * 业务事件去重：同一个 dedupeKey 只允许存在一条消息。
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
