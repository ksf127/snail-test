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
 * <p>至少一次投递意味着同一条消息可能被消费多次，去重键必须是"消息 ID"而不是"业务内容"：
 * 业务内容相同不代表是同一次投递（比如同一订单确实可能产生两条不同事件）。
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
     * @return true 表示本次真正生效；false 表示重复消息或未知 topic
     */
    public boolean accept(long messageId, String topic, String orderNo) {
        if (!processedMessageIds.add(messageId)) {
            duplicates.increment();
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
        return true;
    }

    public Optional<Order.Status> statusOf(String orderNo) {
        return Optional.ofNullable(statusByOrderNo.get(orderNo));
    }

    public Snapshot snapshot() {
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
