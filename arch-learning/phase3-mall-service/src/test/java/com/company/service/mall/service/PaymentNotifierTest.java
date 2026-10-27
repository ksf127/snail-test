package com.company.service.mall.service;

import com.company.service.mall.domain.NotifyTask;
import com.company.service.mall.domain.Payment;
import com.company.service.mall.infra.InMemoryNotificationGateway;
import com.company.service.mall.infra.InMemoryNotifyTaskRepository;
import com.company.service.mall.support.MallProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 最大努力通知：入队幂等、成功、重试、死信。
 */
class PaymentNotifierTest {

    private MallProperties properties;

    private InMemoryNotifyTaskRepository repository;

    private InMemoryNotificationGateway gateway;

    private PaymentNotifier notifier;

    private Payment payment;

    private Instant now;

    @BeforeEach
    void setUp() {
        properties = new MallProperties();
        properties.getNotify().setMaxAttempts(3);
        properties.getNotify().setBaseBackoff(Duration.ofMillis(1));
        properties.getNotify().setMaxBackoff(Duration.ofMillis(2));
        repository = new InMemoryNotifyTaskRepository();
        gateway = new InMemoryNotificationGateway();
        notifier = new PaymentNotifier(repository, gateway, properties);
        now = Instant.now();
        payment = new Payment("PO1", "O1", 42L, 1500L, now);
    }

    @Test
    @DisplayName("同一笔支付重复入队只有一条通知任务")
    void enqueueIsIdempotent() {
        NotifyTask first = notifier.enqueue(payment);
        NotifyTask second = notifier.enqueue(payment);

        assertThat(second.paymentNo()).isEqualTo(first.paymentNo());
        assertThat(repository.count()).isEqualTo(1);
        assertThat(first.target()).isEqualTo("merchant:42");
        assertThat(first.amountCents()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("通知成功标记 SUCCEEDED 并记录投递内容")
    void notifySucceed() {
        notifier.enqueue(payment);
        // 入队用真实时钟，这里把"当前时间"推进到入队之后，保证任务已到期
        now = Instant.now();

        PaymentNotifier.NotifyReport report = notifier.notifyOnce(now);

        assertThat(report).isEqualTo(new PaymentNotifier.NotifyReport(1, 1, 0, 0));
        assertThat(repository.findByPaymentNo("PO1").orElseThrow().status())
                .isEqualTo(NotifyTask.Status.SUCCEEDED);
        assertThat(gateway.delivered()).containsExactly("PO1->merchant:42:1500");
    }

    @Test
    @DisplayName("渠道抖动时退避重试，未到期的任务不会被再次推送")
    void retryWithBackoff() {
        notifier.enqueue(payment);
        gateway.failNext(1);
        now = Instant.now();

        PaymentNotifier.NotifyReport first = notifier.notifyOnce(now);

        assertThat(first).isEqualTo(new PaymentNotifier.NotifyReport(1, 0, 1, 0));
        NotifyTask task = repository.findByPaymentNo("PO1").orElseThrow();
        assertThat(task.status()).isEqualTo(NotifyTask.Status.PENDING);
        assertThat(task.attempts()).isEqualTo(1);
        assertThat(repository.findDue(now, 10)).isEmpty();

        PaymentNotifier.NotifyReport second = notifier.notifyOnce(now.plusSeconds(1));

        assertThat(second).isEqualTo(new PaymentNotifier.NotifyReport(1, 1, 0, 0));
        assertThat(task.status()).isEqualTo(NotifyTask.Status.SUCCEEDED);
        assertThat(gateway.delivered()).hasSize(1);
    }

    @Test
    @DisplayName("达到重试上限进入 DEAD，交由商户主动查询兜底")
    void deadAfterMaxAttempts() {
        notifier.enqueue(payment);
        gateway.failNext(10);
        now = Instant.now();

        notifier.notifyOnce(now);
        notifier.notifyOnce(now.plusSeconds(10));
        PaymentNotifier.NotifyReport third = notifier.notifyOnce(now.plusSeconds(20));

        assertThat(third).isEqualTo(new PaymentNotifier.NotifyReport(1, 0, 0, 1));
        NotifyTask task = repository.findByPaymentNo("PO1").orElseThrow();
        assertThat(task.status()).isEqualTo(NotifyTask.Status.DEAD);
        assertThat(task.attempts()).isEqualTo(3);
        assertThat(repository.countByStatus(NotifyTask.Status.DEAD)).isEqualTo(1);
        assertThat(gateway.delivered()).isEmpty();
    }
}
