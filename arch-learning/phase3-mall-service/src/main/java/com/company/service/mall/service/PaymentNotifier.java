package com.company.service.mall.service;

import com.company.service.mall.domain.NotifyTask;
import com.company.service.mall.domain.Payment;
import com.company.service.mall.infra.NotificationGateway;
import com.company.service.mall.infra.NotifyTaskRepository;
import com.company.service.mall.support.MallProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 最大努力通知：支付成功后尽量把结果推给商户，失败就退避重试，超过上限进死信。
 *
 * <p>它和 outbox 的差别不在实现技巧，而在承诺：
 * outbox 承诺"内部事件最终一定送达"（无限重试 + 人工兜底），
 * 最大努力通知只承诺"我会尽力 N 次"，剩下的靠商户主动查询来对齐 ——
 * 这也是它必须提供"支付结果查询接口"的原因。
 */
@Service
public class PaymentNotifier {

    private static final Logger log = LoggerFactory.getLogger(PaymentNotifier.class);

    private final NotifyTaskRepository repository;

    private final NotificationGateway gateway;

    private final MallProperties properties;

    public PaymentNotifier(NotifyTaskRepository repository, NotificationGateway gateway, MallProperties properties) {
        this.repository = repository;
        this.gateway = gateway;
        this.properties = properties;
    }

    /**
     * 入队，用支付流水号做幂等键：重复支付回调不会产生第二条通知任务。
     */
    public NotifyTask enqueue(Payment payment) {
        Optional<NotifyTask> existing = repository.findByPaymentNo(payment.paymentNo());
        if (existing.isPresent()) {
            return existing.get();
        }
        NotifyTask task = new NotifyTask(
                payment.paymentNo(),
                "merchant:" + payment.userId(),
                payment.amountCents(),
                Instant.now());
        repository.save(task);
        return task;
    }

    public NotifyReport notifyOnce(Instant now) {
        List<NotifyTask> due = repository.findDue(now, properties.getOutbox().getBatchSize());
        int succeeded = 0;
        int retried = 0;
        int dead = 0;
        for (NotifyTask task : due) {
            try {
                gateway.notify(task);
                task.markSucceeded();
                repository.save(task);
                succeeded++;
            } catch (RuntimeException e) {
                int attempt = task.attempts() + 1;
                if (attempt >= properties.getNotify().getMaxAttempts()) {
                    task.markDead(e.getMessage());
                    dead++;
                    log.warn("通知任务进入死信: paymentNo={}, attempts={}, error={}",
                            task.paymentNo(), task.attempts(), e.getMessage());
                } else {
                    task.markRetry(now.plus(backoff(attempt)), e.getMessage());
                    retried++;
                }
                repository.save(task);
            }
        }
        return new NotifyReport(due.size(), succeeded, retried, dead);
    }

    @Scheduled(fixedDelayString = "${company.mall.notify.interval-millis:1000}")
    public void scheduledNotify() {
        if (!properties.getNotify().isEnabled()) {
            return;
        }
        try {
            notifyOnce(Instant.now());
        } catch (RuntimeException e) {
            log.warn("通知任务执行失败", e);
        }
    }

    private Duration backoff(int attempts) {
        Duration base = properties.getNotify().getBaseBackoff();
        Duration max = properties.getNotify().getMaxBackoff();
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        Duration delay = base.multipliedBy(multiplier);
        return delay.compareTo(max) > 0 ? max : delay;
    }

    public record NotifyReport(int picked, int succeeded, int retried, int dead) {
    }
}
