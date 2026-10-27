package com.company.service.mall.domain;

import java.time.Instant;

/**
 * TCC 分支事务记录：一个 (订单, 商品) 组合一条。
 *
 * <p>这张表是 TCC 正确性的地基，负责三件事：
 * <ol>
 *   <li><b>幂等</b>：Confirm/Cancel 重复到达时不再动库存</li>
 *   <li><b>空回滚</b>：Cancel 先于 Try 到达时也要落一条 CANCELLED，
 *       否则 Try 后续补到就会把库存永久占住</li>
 *   <li><b>防悬挂</b>：Try 发现已有 CANCELLED 记录时直接拒绝，
 *       因为这次 Try 的资源没有对应的 Cancel 会来回收</li>
 * </ol>
 *
 * <p>注意这些都是"记录 + 状态判断"能解决的，分布式锁解决的是"同一时刻两个 Confirm"
 * 这类并发问题，两者不是替代关系。
 */
public final class TccTransaction {

    public enum Status {
        TRIED,
        CONFIRMED,
        CANCELLED
    }

    private final String txId;

    private final long productId;

    private final int quantity;

    private final Instant createdAt;

    private volatile Status status;

    private volatile Instant updatedAt;

    public TccTransaction(String txId, long productId, int quantity, Instant createdAt) {
        if (txId == null || txId.isBlank()) {
            throw new IllegalArgumentException("txId 不能为空");
        }
        if (productId <= 0) {
            throw new IllegalArgumentException("productId 必须为正数: " + productId);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 必须为正数: " + quantity);
        }
        this.txId = txId;
        this.productId = productId;
        this.quantity = quantity;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.status = Status.TRIED;
    }

    /**
     * 空回滚：Cancel 先到，Try 还没执行。必须落一条 CANCELLED 记录。
     */
    public static TccTransaction emptyRollback(String txId, long productId, int quantity, Instant now) {
        TccTransaction tx = new TccTransaction(txId, productId, quantity, now);
        tx.status = Status.CANCELLED;
        return tx;
    }

    public synchronized boolean markConfirmed() {
        if (status == Status.CONFIRMED) {
            return false;
        }
        if (status == Status.CANCELLED) {
            throw new IllegalStateException("分支 " + txId + " 已 Cancel，收到 Confirm：数据不一致");
        }
        status = Status.CONFIRMED;
        updatedAt = Instant.now();
        return true;
    }

    public synchronized boolean markCancelled() {
        if (status == Status.CANCELLED) {
            return false;
        }
        if (status == Status.CONFIRMED) {
            // Confirm 之后又 Cancel 是协调器重复投递或乱序，必须暴露出来而不是吞掉
            throw new IllegalStateException("分支 " + txId + " 已 Confirm，收到 Cancel：数据不一致");
        }
        status = Status.CANCELLED;
        updatedAt = Instant.now();
        return true;
    }

    public String txId() {
        return txId;
    }

    public long productId() {
        return productId;
    }

    public int quantity() {
        return quantity;
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
}
