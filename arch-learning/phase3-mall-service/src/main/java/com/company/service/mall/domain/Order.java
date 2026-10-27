package com.company.service.mall.domain;

import java.time.Instant;
import java.util.List;

/**
 * 订单聚合根。
 *
 * <p>状态机（非法迁移一律抛异常，而不是静默忽略）：
 * <pre>
 *   CREATED ──支付成功──> PAID
 *      │
 *      ├──主动取消──> CANCELLED
 *      └──超时未付──> CLOSED
 * </pre>
 *
 * <p>CANCELLED 与 CLOSED 的区别不是文字游戏：
 * <ul>
 *   <li>CANCELLED：用户主动取消（或库存不足系统回滚），库存已经归还</li>
 *   <li>CLOSED：支付超时关单，库存同样归还，但可用于统计"下单未支付"的流失</li>
 * </ul>
 *
 * <p>所有迁移方法都是 {@code synchronized}：状态机属于聚合内部不变量，
 * 不能指望调用方每次都记得加锁。分布式场景下更外层还有单号维度的分布式锁兜底。
 */
public final class Order {

    public enum Status {
        CREATED,
        PAID,
        CANCELLED,
        CLOSED
    }

    private final String orderNo;

    /**
     * 调用方请求号：下单接口的幂等键，同一个 requestId 只允许产生一张订单。
     */
    private final String requestId;

    private final long userId;

    private final List<OrderLine> lines;

    private final long amountCents;

    private final Instant createdAt;

    private volatile Status status;

    private volatile Instant updatedAt;

    private volatile String closeReason;

    public Order(String orderNo, String requestId, long userId, List<OrderLine> lines, Instant createdAt) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("orderNo 不能为空");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("订单至少需要一行商品");
        }
        this.orderNo = orderNo;
        this.requestId = requestId;
        this.userId = userId;
        this.lines = List.copyOf(lines);
        this.amountCents = this.lines.stream().mapToLong(OrderLine::lineAmountCents).sum();
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = Status.CREATED;
    }

    public synchronized boolean markPaid() {
        if (status == Status.PAID) {
            // 支付回调重放：幂等，不重复变更状态
            return false;
        }
        requireStatus(Status.CREATED, "支付");
        status = Status.PAID;
        updatedAt = Instant.now();
        return true;
    }

    public synchronized boolean markCancelled(String reason) {
        if (status == Status.CANCELLED) {
            return false;
        }
        requireStatus(Status.CREATED, "取消");
        status = Status.CANCELLED;
        closeReason = reason;
        updatedAt = Instant.now();
        return true;
    }

    public synchronized boolean markClosed(String reason) {
        if (status == Status.CLOSED) {
            return false;
        }
        requireStatus(Status.CREATED, "关单");
        status = Status.CLOSED;
        closeReason = reason;
        updatedAt = Instant.now();
        return true;
    }

    private void requireStatus(Status expected, String action) {
        if (status != expected) {
            throw new IllegalStateException(
                    "订单 " + orderNo + " 当前状态 " + status + " 不允许" + action);
        }
    }

    public String orderNo() {
        return orderNo;
    }

    public String requestId() {
        return requestId;
    }

    public long userId() {
        return userId;
    }

    public List<OrderLine> lines() {
        return lines;
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

    public Instant updatedAt() {
        return updatedAt;
    }

    public String closeReason() {
        return closeReason;
    }
}
