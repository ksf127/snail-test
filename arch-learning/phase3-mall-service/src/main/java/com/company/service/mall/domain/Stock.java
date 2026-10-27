package com.company.service.mall.domain;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 库存。
 *
 * <p>TCC 的三步对应三种库存变化：
 * <ul>
 *   <li>Try：available -= q，reserved += q（预占，不真正扣减）</li>
 *   <li>Confirm：reserved -= q（真正扣减，available 不再变化）</li>
 *   <li>Cancel：available += q，reserved -= q（归还）</li>
 * </ul>
 *
 * <p>用 CAS 而不是 synchronized：库存行是热点，CAS 至少让失败的线程不阻塞。
 * 真正跨实例的互斥由 TCC 分支上的分布式锁负责，这里的原子性只保证单实例内不超卖。
 */
public final class Stock {

    private final long productId;

    private final int total;

    private final AtomicInteger available;

    private final AtomicInteger reserved = new AtomicInteger();

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
     */
    public boolean reserve(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("预占数量必须为正: " + quantity);
        }
        while (true) {
            int current = available.get();
            if (current < quantity) {
                return false;
            }
            if (available.compareAndSet(current, current - quantity)) {
                reserved.addAndGet(quantity);
                return true;
            }
        }
    }

    public void confirmReserved(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("确认数量必须为正: " + quantity);
        }
        if (reserved.addAndGet(-quantity) < 0) {
            throw new IllegalStateException("确认数量超过预占量: productId=" + productId);
        }
    }

    public void releaseReserved(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("释放数量必须为正: " + quantity);
        }
        if (reserved.addAndGet(-quantity) < 0) {
            throw new IllegalStateException("释放数量超过预占量: productId=" + productId);
        }
        available.addAndGet(quantity);
    }
}
