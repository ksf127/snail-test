package com.company.service.mall.service;

import com.company.service.mall.domain.Order;
import com.company.service.mall.domain.OutboxMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * 订单事件投影（消费端）。
 *
 * <p>【解决什么问题】outbox 是"至少一次投递"，同一条消息可能被消费两次、三次；
 * 生产端无法消除重复，只能在消费端保证"重复消息不产生重复效果"，
 * 否则统计会被重复计数、下游状态会被回退（例如 order.paid 之后又收到 order.created）。
 *
 * <p>【为什么这样设计】去重键必须是"消息 ID"而不是"业务内容"：
 * 业务内容相同不代表是同一次投递（同一订单确实会产生 created/paid 两条不同事件）。
 * 先占位再去重：{@code add(messageId)} 成功才算第一次处理，失败直接返回 false，
 * 这样即使两条重复消息并发到达，也只有一个线程能继续往下走。
 *
 * <p>【怎么读这段代码】{@link #accept(OutboxMessage)} 解析 JSON 后转调
 * {@link #accept(long, String, String)}；后者依次做"去重 → 映射 topic → 更新投影"。
 * 未知 topic 只计数不报错，便于后续新增事件类型而不影响老消费者。
 *
 * <p>注意这里的去重集合是内存的，进程重启后会清空；真实实现用
 * {@code INSERT ... ON DUPLICATE KEY} 或去重表/Redis SETNX，并且要与业务写入在同一个事务里。
 */
@Service
public class OrderEventProjector implements Consumer<OutboxMessage> {

    private static final Logger log = LoggerFactory.getLogger(OrderEventProjector.class);

    private final ObjectMapper objectMapper;

    private final Set<Long> processedMessageIds = ConcurrentHashMap.newKeySet();

    private final ConcurrentMap<String, Order.Status> statusByOrderNo = new ConcurrentHashMap<>();

    private final LongAdder created = new LongAdder();

    private final LongAdder paid = new LongAdder();

    private final LongAdder cancelled = new LongAdder();

    private final LongAdder closed = new LongAdder();

    private final LongAdder duplicates = new LongAdder();

    private final LongAdder ignored = new LongAdder();

    public OrderEventProjector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 从 outbox 消息中解析订单号并投影。
     *
     * <p>幂等性：幂等，按 messageId 去重。
     *
     * @param message 已投递的 outbox 消息
     */
    @Override
    public void accept(OutboxMessage message) {
        try {
            JsonNode payload = objectMapper.readTree(message.payload());
            accept(message.id(), message.topic(), payload.path("orderNo").asText());
        } catch (Exception e) {
            // 坏消息不阻塞后续消息；真实实现应转入死信队列而不是仅打日志
            log.warn("订单事件解析失败: id={}, topic={}", message.id(), message.topic(), e);
        }
    }

    /**
     * 去重 + 投影。先占位再去重，保证并发重复消息只有一个能生效。
     *
     * <p>幂等性：幂等。同一 messageId 第二次进入返回 false，不再累加任何计数。
     *
     * @param messageId 消息 ID（幂等键）
     * @param topic     事件类型
     * @param orderNo   订单号，空白时不更新订单维度投影
     * @return true 表示本次真正生效；false 表示重复消息或未知 topic
     */
    public boolean accept(long messageId, String topic, String orderNo) {
        if (!processedMessageIds.add(messageId)) {
            // 重复投递：只记指标，不再重复累加业务统计
            duplicates.increment();
            log.debug("重复消息已丢弃: messageId={}, topic={}", messageId, topic);
            return false;
        }
        Order.Status target = switch (topic) {
            case "order.created" -> Order.Status.CREATED;
            case "order.paid" -> Order.Status.PAID;
            case "order.cancelled" -> Order.Status.CANCELLED;
            case "order.closed" -> Order.Status.CLOSED;
            default -> null;
        };
        if (target == null) {
            ignored.increment();
            log.debug("忽略未知事件类型: topic={}, messageId={}", topic, messageId);
            return false;
        }
        if (orderNo != null && !orderNo.isBlank()) {
            statusByOrderNo.put(orderNo, target);
        }
        switch (target) {
            case CREATED -> created.increment();
            case PAID -> paid.increment();
            case CANCELLED -> cancelled.increment();
            case CLOSED -> closed.increment();
        }
        log.info("【事件投影】已应用: messageId={}, topic={}, orderNo={}", messageId, topic, orderNo);
        return true;
    }

    /**
     * 查询某订单在投影侧的最新状态。
     *
     * <p>幂等：只读。
     *
     * @return 未投影过时返回空 Optional
     */
    public Optional<Order.Status> statusOf(String orderNo) {
        return Optional.ofNullable(statusByOrderNo.get(orderNo));
    }

    /**
     * 投影侧统计快照，用于和订单主表对照"事件是否全部消费到位"。
     *
     * <p>幂等：只读。
     */
    public Snapshot snapshot() {
        log.debug("订单事件投影快照: created={}, paid={}, cancelled={}, closed={}, duplicates={}",
                created.sum(), paid.sum(), cancelled.sum(), closed.sum(), duplicates.sum());
        return new Snapshot(created.sum(), paid.sum(), cancelled.sum(), closed.sum(),
                duplicates.sum(), ignored.sum(), statusByOrderNo.size());
    }

    public record Snapshot(long created,
                           long paid,
                           long cancelled,
                           long closed,
                           long duplicates,
                           long ignoredTopics,
                           long projectedOrders) {
    }
}
