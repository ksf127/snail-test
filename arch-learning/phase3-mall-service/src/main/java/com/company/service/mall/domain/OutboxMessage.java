package com.company.service.mall.domain;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地消息表（outbox）中的一条待投递消息。
 *
 * <p>【解决什么问题】"业务写库成功"和"消息发出去成功"是两次独立操作，
 * 中间任意一次失败都会造成双写不一致：订单已支付、下游却没收到通知。
 * outbox 把消息和业务写在同一个本地事务里落库，于是"业务成功但消息丢了"被彻底消除；
 * 剩下的是"消息写进去了但还没发出去"，由中继任务（{@code OutboxRelay}）反复重试解决。
 *
 * <p>【为什么这样设计】不在业务事务里直接发 MQ，是因为 MQ 调用不受本地事务控制：
 * 事务回滚了消息却已发出（多发），或者事务提交了消息发送失败（漏发），两者都无法靠数据库回滚修正。
 * 落本地表后，投递变成可重试、可观测、可对账的独立过程，代价是引入了"至少一次投递"，
 * 因此消费端必须按消息 ID 去重。
 *
 * <p>【怎么读这段代码】状态机：{@code PENDING → SENT}，或 {@code PENDING → PENDING（退避重试）}，
 * 超过重试上限转 {@code DEAD} 交给人工。{@code dedupeKey} 是业务事件的自然唯一键
 * （如 {@code order.paid:O123}），有了它，重放支付回调之类的重复调用不会堆出一串内容相同的消息。
 */
public final class OutboxMessage {

    private static final Logger log = LoggerFactory.getLogger(OutboxMessage.class);

    public enum Status {
        PENDING,
        SENT,
        DEAD
    }

    private final long id;

    private final String dedupeKey;

    private final String topic;

    private final String payload;

    private final Instant createdAt;

    private volatile Status status;

    private volatile int attempts;

    private volatile Instant nextAttemptAt;

    private volatile String lastError;

    /**
     * 构造一条待投递消息，初始状态 PENDING，并且立即到期（nextAttemptAt = createdAt）。
     *
     * @param id        消息 ID，消费端用它做幂等键
     * @param dedupeKey 业务去重键，不能为空
     * @param topic     事件名/路由键，不能为空
     * @param payload   消息体（JSON 字符串）
     * @param createdAt 创建时间
     * @throws IllegalArgumentException topic 或 dedupeKey 为空
     */
    public OutboxMessage(long id, String dedupeKey, String topic, String payload, Instant createdAt) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic 不能为空");
        }
        if (dedupeKey == null || dedupeKey.isBlank()) {
            throw new IllegalArgumentException("dedupeKey 不能为空");
        }
        this.id = id;
        this.dedupeKey = dedupeKey;
        this.topic = topic;
        this.payload = payload;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
        this.status = Status.PENDING;
    }

    /**
     * 标记投递成功。只有在 Broker 明确确认之后才能调用。
     *
     * <p>幂等性：已 SENT 时返回 false，不会重复计数。
     *
     * @return true 表示本次真正发生了状态迁移
     * @throws IllegalStateException 消息已进入 DEAD，不允许再标记成功
     */
    public synchronized boolean markSent() {
        if (status == Status.SENT) {
            return false;
        }
        if (status == Status.DEAD) {
            throw new IllegalStateException("消息 " + id + " 已进入 DEAD，不能标记为已发送");
        }
        status = Status.SENT;
        lastError = null;
        log.debug("outbox 消息已发送: id={}, topic={}", id, topic);
        return true;
    }

    /**
     * 投递失败：记录失败原因并推迟到 {@code nextAttemptAt} 再试。
     * 递增 attempts 放在这里，保证"重试次数"与"重试计划"永远一起更新。
     *
     * <p>幂等性：不幂等，每次调用都会让 attempts +1，只能由中继在失败后调用。
     *
     * @param nextAttemptAt 下次可投递时间
     * @param error         失败原因
     */
    public synchronized void markRetry(Instant nextAttemptAt, String error) {
        attempts++;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = error;
        log.debug("outbox 消息退避重试: id={}, attempts={}, next={}", id, attempts, nextAttemptAt);
    }

    /**
     * 超过最大重试次数：进入死信。继续重试只会占着中继线程，
     * 真正需要的是人工介入或补偿任务。
     *
     * <p>幂等性：不幂等，每次调用都会让 attempts +1。
     *
     * @param error 最后一次失败原因
     */
    public synchronized void markDead(String error) {
        attempts++;
        this.status = Status.DEAD;
        this.lastError = error;
        log.warn("outbox 消息转死信: id={}, topic={}, attempts={}, error={}", id, topic, attempts, error);
    }

    public long id() {
        return id;
    }

    public String dedupeKey() {
        return dedupeKey;
    }

    public String topic() {
        return topic;
    }

    public String payload() {
        return payload;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Status status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public String lastError() {
        return lastError;
    }
}
