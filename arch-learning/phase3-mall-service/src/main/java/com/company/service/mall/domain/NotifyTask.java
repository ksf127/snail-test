package com.company.service.mall.domain;

import java.time.Instant;

/**
 * 最大努力通知任务：支付成功后通知商户/下游渠道。
 *
 * <p>与 outbox 的区别是"可靠性等级"：
 * <ul>
 *   <li>outbox 服务的是内部消费者，要求不丢、可重放，靠消息表 + MQ 确认保证</li>
 *   <li>最大努力通知服务的是外部系统，对方不可控，只保证"尽力重试 N 次"，
 *       失败进死信并暴露给运维，不做无限重试（否则任务表会永远增长）</li>
 * </ul>
 *
 * <p>因此它天然需要下游提供查询接口兜底 —— 通知丢了，对方可以主动来查。
 */
public final class NotifyTask {

    public enum Status {
        PENDING,
        SUCCEEDED,
        DEAD
    }

    /**
     * 用支付流水号做自然唯一键：同一笔支付只会有一条通知任务。
     */
    private final String paymentNo;

    private final String target;

    private final long amountCents;

    private final Instant createdAt;

    private volatile Status status;

    private volatile int attempts;

    private volatile Instant nextAttemptAt;

    private volatile String lastError;

    public NotifyTask(String paymentNo, String target, long amountCents, Instant createdAt) {
        if (paymentNo == null || paymentNo.isBlank()) {
            throw new IllegalArgumentException("paymentNo 不能为空");
        }
        this.paymentNo = paymentNo;
        this.target = target;
        this.amountCents = amountCents;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
        this.status = Status.PENDING;
    }

    public synchronized boolean markSucceeded() {
        if (status == Status.SUCCEEDED) {
            return false;
        }
        status = Status.SUCCEEDED;
        lastError = null;
        return true;
    }

    public synchronized void markRetry(Instant nextAttemptAt, String error) {
        attempts++;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = error;
    }

    public synchronized void markDead(String error) {
        attempts++;
        this.status = Status.DEAD;
        this.lastError = error;
    }

    public String paymentNo() {
        return paymentNo;
    }

    public String target() {
        return target;
    }

    public long amountCents() {
        return amountCents;
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
