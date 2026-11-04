package com.company.service.archlab.concurrency;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用 CAS 手写的最小自旋锁：只为了看清 "锁 = 一个状态 + 一条原子指令 + 一块内存语义"。
 *
 * <p>【解决什么问题】临界区互斥。{@code synchronized} 也能做，但它隐藏了实现，
 * 而且竞争时线程会被挂起、唤醒，一次上下文切换在微秒级。
 * 当临界区只有几次加法时，线程切换比"原地转几圈"贵得多，于是就有了自旋锁。
 *
 * <p>【为什么这样设计】三个必须理解的取舍：
 * <ul>
 *   <li>不可重入：重入会自己等自己，直接抛异常，避免线上静默死锁；</li>
 *   <li>不自带公平性：CAS 抢到就是赢，长临界区下可能出现线程饥饿；</li>
 *   <li>只适合极短临界区：自旋期间一直占着 CPU，临界区一长，CPU 直接被打满。</li>
 * </ul>
 * 互为参照：{@code synchronized} 在竞争时会阻塞线程（上下文切换成本），
 * 自旋锁在竞争时消耗 CPU（不希望线程切换）。选哪个取决于临界区长度与竞争强度。
 *
 * <p>【怎么读这段代码】只看两条路径就够了：
 * <ul>
 *   <li>{@link #lock()}：CAS 把 null 换成自己的 Thread 对象，失败就转一圈再来；</li>
 *   <li>{@link #unlock()}：CAS 把自己的 Thread 换回 null，换不回去说明不是持有者。</li>
 * </ul>
 * 想看效果就跑 CasSpinLockTest / CounterComparison 的 SPIN_LOCK 策略，
 * 再用 {@link #totalSpins()} 观察"空转了多少次"。
 */
public final class CasSpinLock {

    private static final Logger log = LoggerFactory.getLogger(CasSpinLock.class);

    /**
     * owner 为 null 表示未加锁；CAS 成功即持锁，AtomicReference 的 volatile 语义顺带提供了可见性。
     */
    private final AtomicReference<Thread> owner = new AtomicReference<>();

    /**
     * 累计自旋次数：用来量化"这把锁到底空转了多少"，也是判断临界区是否过长的信号。
     */
    private final AtomicLong totalSpins = new AtomicLong();

    /**
     * 阻塞式加锁。
     *
     * <p>每自旋 1024 次让出一次 CPU：在 CPU 核数 < 线程数的超订场景下，
     * 纯自旋会把调度器拖死，让一下能显著降低尾延迟。
     *
     * @throws IllegalStateException 当前线程已经持有这把锁（不支持重入）时抛出
     */
    public void lock() {
        Thread current = Thread.currentThread();
        // 重入检测必须放在 CAS 之前：进了自旋循环再检测，就变成"自己等自己"的永久死锁
        if (current == owner.get()) {
            throw new IllegalStateException("CasSpinLock 不支持重入");
        }
        long spins = 0L;
        // 自旋体内绝对不能打日志：日志的 IO / 锁竞争会放大自旋耗时，
        // 也会引入内存屏障，把要观察的"空转成本"完全掩盖掉
        while (!owner.compareAndSet(null, current)) {
            spins++;
            // JDK9+ 的空转提示，告诉 CPU 当前在等待，可降低功耗与超线程争用
            Thread.onSpinWait();
            // 1024 次让一次 CPU，避免在超订环境下把调度器饿死
            if ((spins & 0x3FFL) == 0L) {
                Thread.yield();
            }
        }
        totalSpins.addAndGet(spins);
        // 走到这里说明已经拿到锁，且只有"有竞争"时才打，属低频路径；仍然用 debug，
        // 因为 lock() 本身是高频入口，info 会把日志刷爆
        if (spins > 0L) {
            log.debug("【自旋锁】线程 {} 自旋 {} 次后获得锁", current.getName(), spins);
        }
    }

    /**
     * 尝试加锁，不等待。
     *
     * @return 是否抢到锁；当前线程已持锁或锁被别的线程占用时返回 false
     */
    public boolean tryLock() {
        Thread current = Thread.currentThread();
        // 已持锁时返回 false 而不是抛异常：tryLock 的语义本就是"试一下"，失败是正常结果
        if (current == owner.get()) {
            return false;
        }
        return owner.compareAndSet(null, current);
    }

    /**
     * 解锁：只有持有者才能解，否则说明代码结构已经错了，立即暴露。
     *
     * @throws IllegalStateException 当前线程并未持有这把锁时抛出
     */
    public void unlock() {
        Thread current = Thread.currentThread();
        // 用 CAS(current, null) 而不是直接 set(null)：能顺带校验持有者身份，
        // 避免"没加锁的线程误调 unlock 把别人的锁放掉"这种更难查的 Bug
        if (!owner.compareAndSet(current, null)) {
            throw new IllegalStateException("解锁失败：锁未被当前线程持有");
        }
    }

    /**
     * 只读地探测锁是否被占用，结果随时可能过期，仅用于监控/调试。
     *
     * @return true 表示当前有人持锁
     */
    public boolean isLocked() {
        return owner.get() != null;
    }

    /**
     * 累计自旋次数，用于评估锁竞争强度。
     *
     * @return 从进程启动至今所有加锁操作累计的自旋圈数
     */
    public long totalSpins() {
        return totalSpins.get();
    }
}
