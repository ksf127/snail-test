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
 * <p>【解决什么问题】消息已经和业务数据一起落库，但还没真正发给下游。
 * 中继负责把这段"已持久化、未投递"的窗口补上：只要消息还在 PENDING，
 * 进程重启、Broker 抖动、网络失败都不会让它丢失。
 *
 * <p>【为什么这样设计】为什么不让业务直接发 MQ、省掉中继？因为业务事务与发送无法原子化：
 * 先发后提交会多发，先提交后发会漏发。outbox 把发送移到事务之外，用"轮询 + 确认 + 退避"
 * 把可靠投递变成一个可以重试的独立过程；代价是从"实时发送"变成"最终送达"，
 * 且引入了重复投递的可能，所以消费端必须幂等。
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
 *
 * <p>【怎么读这段代码】定时任务 {@link #scheduledRelay} 每轮调 {@link #relayOnce}：
 * 扫一批到期消息 → 逐条 publish → 成功标记 SENT，失败按尝试次数决定退避还是转 DEAD。
 * 每轮结束会打印"扫到几条 / 成功几条 / 重试几条 / 死信几条"，可直接用来观察积压。
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
     *
     * <p>幂等性：可重复调用。同一条消息若已 SENT 就不会再被 {@code findDue} 扫到；
     * 发送成功但保存状态前崩溃时，下一轮会重发同一消息（至少一次），依赖消费端幂等。
     *
     * @param now 本轮扫描的基准时间，用于判断 nextAttemptAt 是否到期
     * @return 本轮统计：扫到、成功、重试、死信各多少条
     */
    public RelayReport relayOnce(Instant now) {
        List<OutboxMessage> due = repository.findDue(now, properties.getOutbox().getBatchSize());
        int sent = 0;
        int retried = 0;
        int dead = 0;
        for (OutboxMessage message : due) {
            try {
                // 先等 Broker 确认再改状态：确认失败时状态必须保持 PENDING
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
                    // 指数退避：Broker 抖动时不能用固定间隔继续打
                    message.markRetry(now.plus(backoff(attempt)), e.getMessage());
                    retried++;
                    log.debug("outbox 投递失败，稍后重试: id={}, attempts={}, error={}",
                            message.id(), message.attempts(), e.getMessage());
                }
                repository.save(message);
            }
        }
        log.info("【outbox 中继】本轮扫到 {} 条: 发送成功 {} 条, 待重试 {} 条, 转死信 {} 条",
                due.size(), sent, retried, dead);
        return new RelayReport(due.size(), sent, retried, dead);
    }

    /**
     * 定时入口，按固定延迟反复调用 {@link #relayOnce}。
     *
     * <p>用 fixedDelay 而不是 fixedRate：上一轮没跑完时不应该叠加下一轮，
     * 否则 Broker 慢的时候中继线程会越积越多。
     */
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
