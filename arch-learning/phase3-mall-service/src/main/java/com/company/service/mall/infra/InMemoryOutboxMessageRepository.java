package com.company.service.mall.infra;

import com.company.service.mall.domain.OutboxMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 本地消息表的内存实现：主表 + dedupeKey 唯一索引 + 到期扫描。
 *
 * <p>【解决什么问题】中继任务需要不断找出"待发送且已到重试时间"的消息。
 * 没有索引就只能全表扫描，消息一多就会拖垮数据库；没有 dedupeKey 唯一约束，
 * 重放的业务操作会堆出一串内容相同的消息。
 *
 * <p>【为什么这样设计】{@code dedupeIndex} 用 {@code putIfAbsent} 模拟唯一索引，
 * 保证同一业务事件的重复写入被吃掉。{@link #findDue} 在内存里用
 * "status=PENDING + nextAttemptAt<=now + 按到期时间排序"表达 SQL 的
 * {@code WHERE ... ORDER BY ... LIMIT}；真实实现要加 {@code FOR UPDATE SKIP LOCKED}，
 * 让多个中继实例并行拉取而互不阻塞。
 *
 * <p>【怎么读这段代码】写入时主表与去重索引一起写；{@link #findDue} 是纯读过滤，
 * 不改变状态，因此可以被多轮调度安全地重复调用。
 * 日志一律 debug —— 内存实现每个操作都打 info 会刷屏。
 */
@Repository
public class InMemoryOutboxMessageRepository implements OutboxMessageRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryOutboxMessageRepository.class);

    private final ConcurrentMap<Long, OutboxMessage> messages = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, OutboxMessage> dedupeIndex = new ConcurrentHashMap<>();

    /**
     * 保存消息。幂等：同一 id 覆盖；同一 dedupeKey 只保留第一条。
     */
    @Override
    public void save(OutboxMessage message) {
        messages.put(message.id(), message);
        OutboxMessage previous = dedupeIndex.putIfAbsent(message.dedupeKey(), message);
        if (previous != null) {
            log.debug("重复业务事件，已去重: dedupeKey={}, 已有消息 id={}", message.dedupeKey(), previous.id());
        }
        log.debug("写入 outbox: id={}, topic={}, status={}", message.id(), message.topic(), message.status());
    }

    @Override
    public Optional<OutboxMessage> findById(long id) {
        return Optional.ofNullable(messages.get(id));
    }

    @Override
    public Optional<OutboxMessage> findByDedupeKey(String dedupeKey) {
        return Optional.ofNullable(dedupeIndex.get(dedupeKey));
    }

    /**
     * 中继扫描：PENDING 且已到期的消息，按到期时间升序，最多 {@code limit} 条。
     *
     * <p>幂等：只读，可重复调用。返回的是可变对象的引用，中继会直接改它们的状态。
     *
     * @param now   当前时间
     * @param limit 单批上限，防止一轮拉太多把内存/连接打满
     */
    @Override
    public List<OutboxMessage> findDue(Instant now, int limit) {
        return messages.values().stream()
                .filter(message -> message.status() == OutboxMessage.Status.PENDING)
                .filter(message -> !message.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(OutboxMessage::nextAttemptAt).thenComparingLong(OutboxMessage::id))
                .limit(limit)
                .toList();
    }

    @Override
    public List<OutboxMessage> findAll() {
        return messages.values().stream()
                .sorted(Comparator.comparingLong(OutboxMessage::id))
                .toList();
    }

    @Override
    public long countByStatus(OutboxMessage.Status status) {
        return messages.values().stream().filter(message -> message.status() == status).count();
    }

    @Override
    public long count() {
        return messages.size();
    }
}
