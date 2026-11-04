package com.company.service.mall.domain;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 最大努力通知任务：支付成功后通知商户/下游渠道。
 *
 * <p>【解决什么问题】支付结果必须告诉外部商户，但外部系统不由我们控制：
 * 对方可能宕机、限流、回调超时。既不能因为通知失败就回滚支付（钱已经收了），
 * 也不能无限重试把任务表撑爆。
 *
 * <p>【为什么这样设计】与 outbox 的区别是"可靠性等级"：
 * <ul>
 *   <li>outbox 服务的是内部消费者，要求不丢、可重放，靠消息表 + MQ 确认保证</li>
 *   <li>最大努力通知服务的是外部系统，对方不可控，只保证"尽力重试 N 次"，
 *       失败进死信并暴露给运维，不做无限重试（否则任务表会永远增长）</li>
 * </ul>
 * 因此它天然需要下游提供查询接口兜底 —— 通知丢了，对方可以主动来查。
 *
 * <p>【怎么读这段代码】状态机：{@code PENDING → SUCCEEDED}，
 * 或失败后退避重试（仍为 PENDING，attempts +1），超过上限转 {@code DEAD}。
 * 用支付流水号做自然唯一键：同一笔支付只会有一条通知任务。
 */
public final class NotifyTask {

    private static final Logger log = LoggerFactory.getLogger(NotifyTask.class);

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

    /**
     * 构造通知任务，初始状态 PENDING 且立即到期。
     *
     * @param paymentNo   支付流水号（幂等键），不能为空
     * @param target      通知目标（如 merchant:42）
     * @param amountCents 通知金额（分）
     * @param createdAt   创建时间
     * @throws IllegalArgumentException paymentNo 为空
     */
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

    /**
     * 标记通知成功。
     *
     * <p>幂等性：已 SUCCEEDED 时返回 false，不会重复变更。
     *
     * @return true 表示本次真正发生了状态迁移
     */
    public synchronized boolean markSucceeded() {
        if (status == Status.SUCCEEDED) {
            return false;
        }
        status = Status.SUCCEEDED;
        lastError = null;
        log.debug("通知任务成功: paymentNo={}", paymentNo);
        return true;
    }

    /**
     * 通知失败，安排退避重试。
     *
     * <p>幂等性：不幂等，每次调用 attempts +1，只能由通知任务在失败后调用。
     *
     * @param nextAttemptAt 下次尝试时间
     * @param error         失败原因
     */
    public synchronized void markRetry(Instant nextAttemptAt, String error) {
        attempts++;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = error;
        log.debug("通知任务退避重试: paymentNo={}, attempts={}, next={}", paymentNo, attempts, nextAttemptAt);
    }

    /**
     * 超过重试上限，进入死信。
     *
     * <p>幂等性：不幂等，每次调用 attempts +1；进入死信后不应再被扫描到。
     *
     * @param error 最后一次失败原因
     */
    public synchronized void markDead(String error) {
        attempts++;
        this.status = Status.DEAD;
        this.lastError = error;
        log.warn("通知任务转死信: paymentNo={}, attempts={}, error={}", paymentNo, attempts, error);
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
