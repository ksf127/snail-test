package com.company.service.mall.domain;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TCC 分支事务记录：一个 (订单, 商品) 组合一条。
 *
 * <p>【解决什么问题】TCC 的 Confirm/Cancel 由协调器发起，网络抖动、重试、乱序都会导致
 * 同一分支被重复调用，甚至 Cancel 先于 Try 到达。没有一张分支记录表，
 * 就无法判断"这次调用该不该动库存"，库存会被重复扣减或永久冻结。
 *
 * <p>【为什么这样设计】这张表是 TCC 正确性的地基，负责三件事：
 * <ol>
 *   <li><b>幂等</b>：Confirm/Cancel 重复到达时不再动库存</li>
 *   <li><b>空回滚</b>：Cancel 先于 Try 到达时也要落一条 CANCELLED，
 *       否则 Try 后续补到就会把库存永久占住</li>
 *   <li><b>防悬挂</b>：Try 发现已有 CANCELLED 记录时直接拒绝，
 *       因为这次 Try 的资源没有对应的 Cancel 会来回收</li>
 * </ol>
 * 注意这些都是"记录 + 状态判断"能解决的，分布式锁解决的是"同一时刻两个 Confirm"
 * 这类并发问题，两者不是替代关系。
 *
 * <p>【怎么读这段代码】正常路径 {@code TRIED → CONFIRMED}；
 * 回滚路径 {@code TRIED → CANCELLED}；空回滚直接以 {@code CANCELLED} 出生。
 * 任何跨越终态的迁移（CONFIRMED ↔ CANCELLED）都抛异常，暴露协调器乱序。
 */
public final class TccTransaction {

    private static final Logger log = LoggerFactory.getLogger(TccTransaction.class);

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

    /**
     * 构造一条 TRIED 分支记录，调用方必须已经在库存上完成预占。
     *
     * @param txId      分支 ID（orderNo:productId）
     * @param productId 商品 ID
     * @param quantity  预占数量
     * @param createdAt 创建时间
     * @throws IllegalArgumentException 参数非法
     */
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
     *
     * <p>幂等性：不幂等，每次调用都会新建一条记录；调用方要先查是否存在。
     *
     * @param txId      分支 ID
     * @param productId 商品 ID
     * @param quantity  原计划预占的数量
     * @param now       创建时间
     * @return 状态为 CANCELLED 的分支记录
     */
    public static TccTransaction emptyRollback(String txId, long productId, int quantity, Instant now) {
        TccTransaction tx = new TccTransaction(txId, productId, quantity, now);
        tx.status = Status.CANCELLED;
        return tx;
    }

    /**
     * 标记 Confirm 完成。
     *
     * <p>幂等性：已 CONFIRMED 时返回 false，调用方据此跳过重复扣减。
     *
     * @return true 表示本次真正发生了状态迁移
     * @throws IllegalStateException 分支已 Cancel，说明协调器乱序
     */
    public synchronized boolean markConfirmed() {
        if (status == Status.CONFIRMED) {
            return false;
        }
        if (status == Status.CANCELLED) {
            throw new IllegalStateException("分支 " + txId + " 已 Cancel，收到 Confirm：数据不一致");
        }
        status = Status.CONFIRMED;
        updatedAt = Instant.now();
        log.debug("TCC 分支标记 Confirm: txId={}", txId);
        return true;
    }

    /**
     * 标记 Cancel 完成。
     *
     * <p>幂等性：已 CANCELLED 时返回 false，调用方据此跳过重复归还。
     *
     * @return true 表示本次真正发生了状态迁移
     * @throws IllegalStateException 分支已 Confirm，说明协调器乱序
     */
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
        log.debug("TCC 分支标记 Cancel: txId={}", txId);
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
