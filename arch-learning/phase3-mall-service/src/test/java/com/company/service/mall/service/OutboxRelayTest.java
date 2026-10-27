package com.company.service.mall.service;

import com.company.service.mall.domain.OutboxMessage;
import com.company.service.mall.infra.InMemoryMessagePublisher;
import com.company.service.mall.infra.InMemoryOutboxMessageRepository;
import com.company.service.mall.support.MallProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * outbox 中继的重试、退避与死信边界。
 */
class OutboxRelayTest {

    private MallProperties properties;

    private InMemoryOutboxMessageRepository repository;

    private InMemoryMessagePublisher publisher;

    private OutboxRelay relay;

    private Instant now;

    @BeforeEach
    void setUp() {
        properties = new MallProperties();
        properties.getOutbox().setMaxAttempts(3);
        properties.getOutbox().setBaseBackoff(Duration.ofMillis(1));
        properties.getOutbox().setMaxBackoff(Duration.ofMillis(2));
        repository = new InMemoryOutboxMessageRepository();
        publisher = new InMemoryMessagePublisher();
        relay = new OutboxRelay(repository, publisher, properties);
        now = Instant.now();
    }

    @Test
    @DisplayName("投递失败先重试，Broker 恢复后标记 SENT")
    void retryThenSucceed() {
        OutboxMessage message = message(1L, "order.created:O1");
        repository.save(message);
        publisher.failNext(1);

        OutboxRelay.RelayReport first = relay.relayOnce(now);

        assertThat(first).isEqualTo(new OutboxRelay.RelayReport(1, 0, 1, 0));
        assertThat(message.status()).isEqualTo(OutboxMessage.Status.PENDING);
        assertThat(message.attempts()).isEqualTo(1);
        assertThat(message.nextAttemptAt()).isAfter(now);
        // 未到期的消息不会被重复拉取，避免中继线程空转打 Broker
        assertThat(repository.findDue(now, 10)).isEmpty();

        OutboxRelay.RelayReport second = relay.relayOnce(now.plusSeconds(1));

        assertThat(second).isEqualTo(new OutboxRelay.RelayReport(1, 1, 0, 0));
        assertThat(message.status()).isEqualTo(OutboxMessage.Status.SENT);
        assertThat(publisher.published()).hasSize(1);
        assertThat(publisher.published().get(0).dedupeKey()).isEqualTo("order.created:O1");
    }

    @Test
    @DisplayName("连续失败到上限进入 DEAD，之后不再被扫描")
    void deadAfterMaxAttempts() {
        OutboxMessage message = message(2L, "order.paid:O2");
        repository.save(message);
        publisher.failNext(10);

        relay.relayOnce(now);
        relay.relayOnce(now.plusSeconds(10));
        OutboxRelay.RelayReport third = relay.relayOnce(now.plusSeconds(20));

        assertThat(third).isEqualTo(new OutboxRelay.RelayReport(1, 0, 0, 1));
        assertThat(message.status()).isEqualTo(OutboxMessage.Status.DEAD);
        assertThat(message.attempts()).isEqualTo(3);
        assertThat(message.lastError()).contains("Broker");
        assertThat(repository.findDue(now.plusSeconds(60), 10)).isEmpty();
        assertThat(repository.countByStatus(OutboxMessage.Status.DEAD)).isEqualTo(1);
    }

    @Test
    @DisplayName("一批到期消息按 nextAttemptAt 排序处理，不会越过 batchSize")
    void batchLimitRespected() {
        properties.getOutbox().setBatchSize(2);
        repository.save(message(1L, "order.created:O1"));
        repository.save(message(2L, "order.created:O2"));
        repository.save(message(3L, "order.created:O3"));

        OutboxRelay.RelayReport report = relay.relayOnce(now);

        assertThat(report.picked()).isEqualTo(2);
        assertThat(report.sent()).isEqualTo(2);
        assertThat(repository.countByStatus(OutboxMessage.Status.PENDING)).isEqualTo(1);
    }

    private OutboxMessage message(long id, String dedupeKey) {
        return new OutboxMessage(id, dedupeKey, dedupeKey.substring(0, dedupeKey.indexOf(':')),
                "{\"orderNo\":\"O1\"}", now);
    }
}
