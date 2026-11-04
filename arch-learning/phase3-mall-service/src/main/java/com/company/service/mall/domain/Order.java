package com.company.service.mall.domain;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 订单聚合根。
 *
 * <p>【解决什么问题】把"订单当前处于什么状态、允许做什么操作"收拢到一处。
 * 支付、取消、超时关单是三个并发入口，如果每个调用方各自判断状态，
 * 就一定会出现"先取消后支付""重复支付"之类的脏数据。
 *
 * <p>【为什么这样设计】用聚合内状态机 + 非法迁移抛异常，而不是返回 false 静默忽略：
 * 状态错乱是分布式链路里最难排查的问题，必须尽早暴露。CANCELLED 与 CLOSED 的区别也不是文字游戏：
 * <ul>
 *   <li>CANCELLED：用户主动取消（或库存不足系统回滚），库存已经归还</li>
 *   <li>CLOSED：支付超时关单，库存同样归还，但可用于统计"下单未支付"的流失</li>
 * </ul>
 * 所有迁移方法都是 {@code synchronized}：状态机属于聚合内部不变量，
 * 不能指望调用方每次都记得加锁。分布式场景下更外层还有单号维度的分布式锁兜底。
 *
 * <p>【怎么读这段代码】构造函数固定金额与明细，状态从 {@code CREATED} 开始；
 * 之后只能走 {@code CREATED → PAID}、{@code CREATED → CANCELLED}、{@code CREATED → CLOSED}
 * 三条边，终态不可再变。
 *
 * <p>状态机（非法迁移一律抛异常，而不是静默忽略）：
 * <pre>
 *   CREATED ──支付成功──> PAID
 *      │
 *      ├──主动取消──> CANCELLED
 *      └──超时未付──> CLOSED
 * </pre>
 */
public final class Order {

    private static final Logger log = LoggerFactory.getLogger(Order.class);

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

    /**
     * 构造订单，状态固定从 {@code CREATED} 开始。
     *
     * @param orderNo   订单号
     * @param requestId 幂等键
     * @param userId    下单用户
     * @param lines     订单行，至少一条，内部做不可变拷贝
     * @param createdAt 创建时间
     * @throws IllegalArgumentException 订单号为空或明细为空
     */
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
        // 金额在构造时一次算死：后续商品调价不影响历史订单
        this.amountCents = this.lines.stream().mapToLong(OrderLine::lineAmountCents).sum();
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = Status.CREATED;
    }

    /**
     * 标记支付成功。
     *
     * <p>幂等性：已 PAID 时返回 false 且不再变更（支付回调重放是常态）。
     *
     * @return true 表示本次真正发生了状态迁移；false 表示重复调用
     * @throws IllegalStateException 当前不是 CREATED（如已取消/已关单）
     */
    public synchronized boolean markPaid() {
        if (status == Status.PAID) {
            // 支付回调重放：幂等，不重复变更状态
            return false;
        }
        requireStatus(Status.CREATED, "支付");
        status = Status.PAID;
        updatedAt = Instant.now();
        log.debug("订单状态迁移: orderNo={}, {} -> PAID", orderNo, Status.CREATED);
        return true;
    }

    /**
     * 标记主动取消。
     *
     * <p>幂等性：已 CANCELLED 时返回 false。
     *
     * @param reason 取消原因，会原样落库供排查
     * @return true 表示本次真正发生了状态迁移
     * @throws IllegalStateException 当前不是 CREATED
     */
    public synchronized boolean markCancelled(String reason) {
        if (status == Status.CANCELLED) {
            return false;
        }
        requireStatus(Status.CREATED, "取消");
        status = Status.CANCELLED;
        closeReason = reason;
        updatedAt = Instant.now();
        log.debug("订单状态迁移: orderNo={}, CREATED -> CANCELLED, reason={}", orderNo, reason);
        return true;
    }

    /**
     * 标记超时关单。
     *
     * <p>幂等性：已 CLOSED 时返回 false。
     *
     * @param reason 关单原因，通常是"支付超时"
     * @return true 表示本次真正发生了状态迁移
     * @throws IllegalStateException 当前不是 CREATED
     */
    public synchronized boolean markClosed(String reason) {
        if (status == Status.CLOSED) {
            return false;
        }
        requireStatus(Status.CREATED, "关单");
        status = Status.CLOSED;
        closeReason = reason;
        updatedAt = Instant.now();
        log.debug("订单状态迁移: orderNo={}, CREATED -> CLOSED, reason={}", orderNo, reason);
        return true;
    }

    /**
     * 状态机守卫：不允许的迁移直接抛异常，避免脏状态被静默写入。
     */
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
