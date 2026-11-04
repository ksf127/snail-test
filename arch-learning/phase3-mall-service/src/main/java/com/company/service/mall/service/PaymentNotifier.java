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
 * <p>【解决什么问题】支付完成后必须把结果告诉外部商户，但对方系统可能宕机、限流、
 * 响应超时。既不能因为通知失败就回滚支付（钱已经收了），也不能无限重试把任务表撑爆。
 *
 * <p>【为什么这样设计】它和 outbox 的差别不在实现技巧，而在承诺：
 * outbox 承诺"内部事件最终一定送达"（无限重试 + 人工兜底），
 * 最大努力通知只承诺"我会尽力 N 次"，剩下的靠商户主动查询来对齐 ——
 * 这也是它必须提供"支付结果查询接口"的原因。
 * 因此它用 paymentNo 作幂等键：重复回调只会命中同一条任务，不会重复通知。
 *
 * <p>【怎么读这段代码】
 * <ul>
 *   <li>{@link #enqueue}：支付成功后落一条 PENDING 任务（已存在则直接返回）</li>
 *   <li>{@link #notifyOnce}：扫一批到期任务 → 逐条发送 → 成功 SUCCEEDED，
 *       失败退避重试，超过上限 DEAD</li>
 *   <li>{@link #scheduledNotify}：定时驱动，每轮打印"扫到/成功/重试/死信"四个数</li>
 * </ul>
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
     *
     * <p>幂等性：幂等。相同 paymentNo 再次调用直接返回已存在的任务，不会重置重试次数。
     *
     * @param payment 支付流水
     * @return 已存在或新建的通知任务
     */
    public NotifyTask enqueue(Payment payment) {
        Optional<NotifyTask> existing = repository.findByPaymentNo(payment.paymentNo());
        if (existing.isPresent()) {
            log.debug("通知任务已存在，跳过入队: paymentNo={}, status={}",
                    payment.paymentNo(), existing.get().status());
            return existing.get();
        }
        NotifyTask task = new NotifyTask(
                payment.paymentNo(),
                "merchant:" + payment.userId(),
                payment.amountCents(),
                Instant.now());
        repository.save(task);
        log.info("【通知】入队成功: paymentNo={}, target={}, amount={}",
                task.paymentNo(), task.target(), task.amountCents());
        return task;
    }

    /**
     * 扫一轮到期任务并逐条发送。
     *
     * <p>幂等性：可重复调用。已 SUCCEEDED 的任务不会被 {@code findDue} 扫到；
     * 发送成功但保存状态前崩溃时会重发，依赖商户侧按 paymentNo 去重。
     *
     * @param now 本轮扫描基准时间
     * @return 本轮统计：扫到、成功、重试、死信各多少条
     */
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
                    // 退避而不是立刻重试：商户侧故障时，密集重试只会让情况更糟
                    task.markRetry(now.plus(backoff(attempt)), e.getMessage());
                    retried++;
                    log.debug("通知失败，稍后重试: paymentNo={}, attempts={}, error={}",
                            task.paymentNo(), task.attempts(), e.getMessage());
                }
                repository.save(task);
            }
        }
        log.info("【支付通知】本轮扫到 {} 条: 成功 {} 条, 待重试 {} 条, 转死信 {} 条",
                due.size(), succeeded, retried, dead);
        return new NotifyReport(due.size(), succeeded, retried, dead);
    }

    /**
     * 定时入口，固定延迟驱动 {@link #notifyOnce}。
     *
     * <p>异常必须在这里兜住：定时任务抛出的异常只会留下堆栈，调度仍会继续，
     * 很容易被忽略成"偶发失败"。
     */
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
