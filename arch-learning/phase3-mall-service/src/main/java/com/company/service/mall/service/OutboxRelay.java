package com.company.service.mall.service;

import com.company.service.mall.domain.OutboxMessage;
import com.company.service.mall.infra.MessagePublisher;
import com.company.service.mall.infra.OutboxMessageRepository;
import com.company.service.mall.support.MallProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * outbox 中继：把本地消息表里"已落库但未投递"的消息送到 Broker。
 *
 * <p>状态机只有三条边：
 * <pre>
 *   PENDING --成功--> SENT
 *   PENDING --失败--> PENDING(推迟 nextAttemptAt，重试次数 +1)
 *   PENDING --超过上限--> DEAD
 * </pre>
 *
 * <p>两个容易踩的坑：
 * <ul>
 *   <li>必须先等 Broker Confirm 再标记 SENT，否则 Broker 挂了消息会被静默丢掉</li>
 *   <li>重试必须退避，否则 Broker 抖动时会被中继线程打成雪崩</li>
 * </ul>
 *
 * <p>这里是"至少一次"投递：确认超时但 Broker 实际已收到时，消息会重复。
 * 这正是消费端必须幂等的原因，而不是发送端可以含糊的理由。
 */
@Service
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxMessageRepository repository;

    private final MessagePublisher publisher;

    private final MallProperties properties;

    public OutboxRelay(OutboxMessageRepository repository, MessagePublisher publisher, MallProperties properties) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
    }

    /**
     * 扫一轮到期消息。抽成独立方法而不是只靠定时任务，
     * 是为了让测试与运维接口都能确定性地驱动它。
     */
    public RelayReport relayOnce(Instant now) {
        List<OutboxMessage> due = repository.findDue(now, properties.getOutbox().getBatchSize());
        int sent = 0;
        int retried = 0;
        int dead = 0;
        for (OutboxMessage message : due) {
            try {
                publisher.publish(message);
                message.markSent();
                repository.save(message);
                sent++;
            } catch (RuntimeException e) {
                int attempt = message.attempts() + 1;
                if (attempt >= properties.getOutbox().getMaxAttempts()) {
                    message.markDead(e.getMessage());
                    dead++;
                    log.warn("outbox 消息进入死信: id={}, topic={}, attempts={}, error={}",
                            message.id(), message.topic(), message.attempts(), e.getMessage());
                } else {
                    message.markRetry(now.plus(backoff(attempt)), e.getMessage());
                    retried++;
                    log.debug("outbox 投递失败，稍后重试: id={}, attempts={}, error={}",
                            message.id(), message.attempts(), e.getMessage());
                }
                repository.save(message);
            }
        }
        return new RelayReport(due.size(), sent, retried, dead);
    }

    @Scheduled(fixedDelayString = "${company.mall.outbox.interval-millis:500}")
    public void scheduledRelay() {
        if (!properties.getOutbox().isEnabled()) {
            return;
        }
        try {
            relayOnce(Instant.now());
        } catch (RuntimeException e) {
            // 定时任务里必须兜住异常：抛出去会让后续调度继续执行，但日志里只有一行堆栈，很难定位
            log.warn("outbox 中继执行失败", e);
        }
    }

    /**
     * 指数退避：base、2*base、4*base ... 上限 maxBackoff。
     */
    private Duration backoff(int attempts) {
        Duration base = properties.getOutbox().getBaseBackoff();
        Duration max = properties.getOutbox().getMaxBackoff();
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        Duration delay = base.multipliedBy(multiplier);
        return delay.compareTo(max) > 0 ? max : delay;
    }

    public record RelayReport(int picked, int sent, int retried, int dead) {
    }
}
