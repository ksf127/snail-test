package com.company.service.mall.service;

import com.company.service.mall.domain.Order;
import com.company.service.mall.domain.OutboxMessage;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消费端幂等：去重键是消息 ID，不是业务内容。
 */
class OrderEventProjectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final OrderEventProjector projector = new OrderEventProjector(objectMapper);

    @Test
    @DisplayName("同一条消息重复投递只生效一次，重复次数被记录")
    void duplicateMessageIgnored() {
        assertThat(projector.accept(1L, "order.created", "O1")).isTrue();
        assertThat(projector.accept(1L, "order.created", "O1")).isFalse();

        assertThat(projector.statusOf("O1")).contains(Order.Status.CREATED);
        assertThat(projector.snapshot().created()).isEqualTo(1);
        assertThat(projector.snapshot().duplicates()).isEqualTo(1);
    }

    @Test
    @DisplayName("同一订单的不同事件是不同的消息，按顺序推进投影状态")
    void eventsAdvanceProjection() {
        assertThat(projector.accept(1L, "order.created", "O1")).isTrue();
        assertThat(projector.accept(2L, "order.paid", "O1")).isTrue();

        assertThat(projector.statusOf("O1")).contains(Order.Status.PAID);
        assertThat(projector.snapshot()).isEqualTo(
                new OrderEventProjector.Snapshot(1, 1, 0, 0, 0, 0, 1));
    }

    @Test
    @DisplayName("未知 topic 被忽略，不影响已有投影")
    void unknownTopicIgnored() {
        assertThat(projector.accept(1L, "order.created", "O1")).isTrue();
        assertThat(projector.accept(2L, "order.refunded", "O1")).isFalse();

        assertThat(projector.statusOf("O1")).contains(Order.Status.CREATED);
        assertThat(projector.snapshot().ignoredTopics()).isEqualTo(1);
    }

    @Test
    @DisplayName("从 outbox 消息解析 orderNo 后投影，坏消息不阻塞后续消息")
    void projectFromOutboxMessage() {
        OutboxMessage good = new OutboxMessage(1L, "order.created:O1", "order.created",
                "{\"orderNo\":\"O1\"}", Instant.now());
        OutboxMessage broken = new OutboxMessage(2L, "order.paid:O2", "order.paid",
                "not-json", Instant.now());
        OutboxMessage paid = new OutboxMessage(3L, "order.paid:O1", "order.paid",
                "{\"orderNo\":\"O1\"}", Instant.now());

        projector.accept(good);
        projector.accept(broken);
        projector.accept(paid);

        assertThat(projector.statusOf("O1")).contains(Order.Status.PAID);
        assertThat(projector.statusOf("O2")).isEmpty();
        assertThat(projector.snapshot().paid()).isEqualTo(1);
    }
}
