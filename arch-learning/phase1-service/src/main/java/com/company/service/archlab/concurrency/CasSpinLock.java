package com.company.service.archlab.concurrency;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用 CAS 手写的最小自旋锁：只为了看清 "锁 = 一个状态 + 一条原子指令 + 一块内存语义"。
 *
 * <p>三个必须理解的取舍：
 * <ul>
 *   <li>不可重入：重入会自己等自己，直接抛异常，避免线上静默死锁；</li>
 *   <li>不自带公平性：CAS 抢到就是赢，长临界区下可能出现线程饥饿；</li>
 *   <li>只适合极短临界区：自旋期间一直占着 CPU，临界区一长，CPU 直接被打满。</li>
 * </ul>
 *
 * <p>互为参照：{@code synchronized} 在竞争时会阻塞线程（上下文切换成本），
 * 自旋锁在竞争时消耗 CPU（不希望线程切换）。选哪个取决于临界区长度与竞争强度。
 */
public final class CasSpinLock {

    /** owner 为 null 表示未加锁；CAS 成功即持锁，AtomicReference 的 volatile 语义顺带提供了可见性。 */
    private final AtomicReference<Thread> owner = new AtomicReference<>();

    /** 累计自旋次数：用来量化"这把锁到底空转了多少"，也是判断临界区是否过长的信号。 */
    private final AtomicLong totalSpins = new AtomicLong();

    /**
     * 阻塞式加锁。
     *
     * <p>每自旋 1024 次让出一次 CPU：在 CPU 核数 < 线程数的超订场景下，
     * 纯自旋会把调度器拖死，让一下能显著降低尾延迟。
     */
    public void lock() {
        Thread current = Thread.currentThread();
        if (current == owner.get()) {
            throw new IllegalStateException("CasSpinLock 不支持重入");
        }
        long spins = 0L;
        while (!owner.compareAndSet(null, current)) {
            spins++;
            Thread.onSpinWait();
            if ((spins & 0x3FFL) == 0L) {
                Thread.yield();
            }
        }
        totalSpins.addAndGet(spins);
    }

    /**
     * 尝试加锁，不等待。
     *
     * @return 是否抢到锁
     */
    public boolean tryLock() {
        Thread current = Thread.currentThread();
        if (current == owner.get()) {
            return false;
        }
        return owner.compareAndSet(null, current);
    }

    /**
     * 解锁：只有持有者才能解，否则说明代码结构已经错了，立即暴露。
     */
    public void unlock() {
        Thread current = Thread.currentThread();
        if (!owner.compareAndSet(current, null)) {
            throw new IllegalStateException("解锁失败：锁未被当前线程持有");
        }
    }

    public boolean isLocked() {
        return owner.get() != null;
    }

    public long totalSpins() {
        return totalSpins.get();
    }
}
