package com.company.service.mall.domain;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 库存。
 *
 * <p>【解决什么问题】库存是典型的"不能超卖"资源：同一件商品可能被上万人同时下单，
 * 必须保证"可用库存 ≥ 0"且"预占 + 已售 ≤ 总量"，否则就会出现超卖。
 *
 * <p>【为什么这样设计】把库存拆成"可用"和"冻结"两个数，正是 TCC 的 Try 需要的东西：
 * 预占只是把数量从 available 挪到 reserved，并不真正扣减，所以随时可以退还。
 * 用 CAS 而不是 synchronized：库存行是热点，CAS 至少让失败的线程不阻塞。
 * 真正跨实例的互斥由 TCC 分支上的分布式锁负责，这里的原子性只保证单实例内不超卖。
 *
 * <p>【怎么读这段代码】状态迁移对应 TCC 三阶段：
 * <ul>
 *   <li>Try：{@link #reserve} → available -= q，reserved += q（预占，不真正扣减）</li>
 *   <li>Confirm：{@link #confirmReserved} → reserved -= q（真正扣减，available 不再变化）</li>
 *   <li>Cancel：{@link #releaseReserved} → available += q，reserved -= q（归还）</li>
 * </ul>
 */
public final class Stock {

    private static final Logger log = LoggerFactory.getLogger(Stock.class);

    private final long productId;

    private final int total;

    private final AtomicInteger available;

    private final AtomicInteger reserved = new AtomicInteger();

    /**
     * @param productId 商品 ID
     * @param total     初始库存总量，不能为负
     * @throws IllegalArgumentException 总量为负
     */
    public Stock(long productId, int total) {
        if (total < 0) {
            throw new IllegalArgumentException("库存不能为负: " + total);
        }
        this.productId = productId;
        this.total = total;
        this.available = new AtomicInteger(total);
    }

    public long productId() {
        return productId;
    }

    public int total() {
        return total;
    }

    public int available() {
        return available.get();
    }

    public int reserved() {
        return reserved.get();
    }

    /**
     * 预占库存：失败时不产生任何副作用，调用方负责决定是否补偿同一事务内已成功的分支。
     *
     * <p>幂等性：不幂等。每次成功调用都会真实占用一份库存，不能重试同一个预占请求。
     *
     * @param quantity 预占数量，必须为正
     * @return true 预占成功；false 可用库存不足或商品不存在（由调用方处理）
     * @throws IllegalArgumentException 数量非正
     */
    public boolean reserve(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("预占数量必须为正: " + quantity);
        }
        // CAS 自旋：只有"读到的值仍然可用"时才扣减，失败就重读，避免超卖
        while (true) {
            int current = available.get();
            if (current < quantity) {
                log.debug("库存不足，预占失败: productId={}, 可用={}, 需要={}", productId, current, quantity);
                return false;
            }
            if (available.compareAndSet(current, current - quantity)) {
                reserved.addAndGet(quantity);
                log.debug("库存预占成功: productId={}, 扣减={}, 剩余可用={}", productId, quantity, available.get());
                return true;
            }
        }
    }

    /**
     * 确认扣减：把预占的库存真正消耗掉（TCC 的 Confirm）。
     *
     * <p>幂等性：不幂等。重复 Confirm 会再次减少 reserved，必须靠分支记录拦截重复调用。
     *
     * @param quantity 确认数量，必须为正
     * @throws IllegalArgumentException 数量非正
     * @throws IllegalStateException    确认数量超过已预占量（数据不一致）
     */
    public void confirmReserved(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("确认数量必须为正: " + quantity);
        }
        // 先扣再判断：reserved 变成负数说明 Confirm 多于 Try，属于严重的数据不一致
        if (reserved.addAndGet(-quantity) < 0) {
            throw new IllegalStateException("确认数量超过预占量: productId=" + productId);
        }
        log.debug("库存确认扣减: productId={}, 数量={}, 剩余冻结={}", productId, quantity, reserved.get());
    }

    /**
     * 释放预占：把冻结的库存还回可用（TCC 的 Cancel）。
     *
     * <p>幂等性：不幂等。重复调用会把库存凭空放大，必须靠分支记录拦截。
     *
     * @param quantity 释放数量，必须为正
     * @throws IllegalArgumentException 数量非正
     * @throws IllegalStateException    释放数量超过已预占量
     */
    public void releaseReserved(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("释放数量必须为正: " + quantity);
        }
        if (reserved.addAndGet(-quantity) < 0) {
            throw new IllegalStateException("释放数量超过预占量: productId=" + productId);
        }
        available.addAndGet(quantity);
        log.debug("库存释放预占: productId={}, 数量={}, 剩余可用={}", productId, quantity, available.get());
    }
}
