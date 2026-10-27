package com.company.service.mall.domain;

import java.time.Instant;

/**
 * 本地消息表（outbox）中的一条待投递消息。
 *
 * <p>核心思想：业务写库与消息落库在同一个本地事务里，于是"业务成功但消息丢了"
 * 这种双写不一致被彻底消除；剩下的是"消息写进去了但还没发出去"，
 * 由中继任务（{@code OutboxRelay}）反复重试解决。
 *
 * <p>{@code dedupeKey} 是业务事件的自然唯一键（如 {@code order.paid:O123}）。
 * 有了它，重放支付回调之类的重复调用不会堆出一串内容相同的消息。
 */
public final class OutboxMessage {

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

    public synchronized boolean markSent() {
        if (status == Status.SENT) {
            return false;
        }
        if (status == Status.DEAD) {
            throw new IllegalStateException("消息 " + id + " 已进入 DEAD，不能标记为已发送");
        }
        status = Status.SENT;
        lastError = null;
        return true;
    }

    /**
     * 投递失败：记录失败原因并推迟到 {@code nextAttemptAt} 再试。
     * 递增 attempts 放在这里，保证"重试次数"与"重试计划"永远一起更新。
     */
    public synchronized void markRetry(Instant nextAttemptAt, String error) {
        attempts++;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = error;
    }

    /**
     * 超过最大重试次数：进入死信。继续重试只会占着中继线程，
     * 真正需要的是人工介入或补偿任务。
     */
    public synchronized void markDead(String error) {
        attempts++;
        this.status = Status.DEAD;
        this.lastError = error;
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
