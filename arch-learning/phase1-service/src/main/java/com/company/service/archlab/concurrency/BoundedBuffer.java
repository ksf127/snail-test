package com.company.service.archlab.concurrency;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 手写有界缓冲区（生产者/消费者），用 {@link Condition} 而不是 wait/notify。
 *
 * <p>【解决什么问题】生产者写满、消费者读空时必须"等"，而且只能等自己关心的那个条件；
 * 同时队列内部的 putIndex/takeIndex/count 又是多线程共享的可变状态。
 * 这个类要同时解决两件事：互斥（同一时刻只有一个线程改数组）和条件等待（不满/不空）。
 *
 * <p>【为什么这样设计】为什么不用 wait/notify：
 * <ul>
 *   <li>wait/notify 绑定在对象监视器上，一个对象只能有一个等待队列，
 *       生产者与消费者会互相唤醒（惊群），必须靠 notifyAll 兜底；</li>
 *   <li>Condition 可以按"队列不满 / 队列不空"拆成两个等待队列，
 *       {@code signal()} 就只唤醒对方阵营，语义清晰且开销更小。</li>
 * </ul>
 * 代价是必须自己管理 lock/unlock，所以每个方法都用 try/finally 包住，
 * 避免异常路径漏掉解锁导致整个队列永久卡死。
 *
 * <p>注意 while 而不是 if：被唤醒不代表条件成立（可能是虚假唤醒、也可能被别人先抢走），
 * 必须重新判断，这是并发代码最高频的 Bug 来源之一。
 *
 * <p>【怎么读这段代码】先看两个字段 {@code notFull}/{@code notEmpty} 的分工，
 * 再按 {@link #put(Object)} → {@link #take()} 的顺序读：
 * 两边都是"加锁 → while 判条件 → 改状态 → signal 对方 → finally 解锁"这同一套骨架。
 * 测试里的 BoundedBufferTest 就是用一个生产者 + 一个消费者跑这套骨架。
 */
public final class BoundedBuffer<E> {

    private static final Logger log = LoggerFactory.getLogger(BoundedBuffer.class);

    private final Object[] items;

    private int putIndex;

    private int takeIndex;

    private int count;

    private final ReentrantLock lock = new ReentrantLock();

    private final Condition notFull = lock.newCondition();

    private final Condition notEmpty = lock.newCondition();

    /**
     * 创建一个固定容量的环形缓冲区。
     *
     * @param capacity 缓冲区容量，必须大于 0
     * @throws IllegalArgumentException capacity 小于等于 0 时抛出
     */
    public BoundedBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须大于 0");
        }
        this.items = new Object[capacity];
        // 构造只发生一次，打 info 不会污染后续高频路径，还能在排查"队列容量配错"时直接看到结果
        log.info("【有界缓冲区】创建完成，容量={}", capacity);
    }

    /**
     * 入队一个元素；队列满时阻塞等待，直到有消费者取走元素。
     *
     * @param value 待入队元素，不允许为 null（null 会被当作"空槽位"标记，见 take 的清空逻辑）
     * @throws InterruptedException 等待期间线程被中断时抛出，中断标记会被清除
     */
    public void put(E value) throws InterruptedException {
        // lockInterruptibly 而不是 lock：阻塞在"拿锁"阶段也要能被中断，
        // 否则服务优雅停机时线程会卡在这里一直不退出
        lock.lockInterruptibly();
        try {
            // 必须 while：被 signal 唤醒后条件可能已经不成立，要重新判断
            while (count == items.length) {
                // 这里绝对不能打日志：每次队列满都会走到，且 await 前后打日志会改变线程调度时序，
                // 让"阻塞/唤醒"的对照实验失真
                notFull.await();
            }
            items[putIndex] = value;
            // 环形下标手写回绕，避免取模运算；容量为 1 时也能正确回到 0
            if (++putIndex == items.length) {
                putIndex = 0;
            }
            count++;
            // 只唤醒消费者阵营，而不是 notifyAll 把所有线程都叫起来再抢锁
            notEmpty.signal();
        } finally {
            // 无论正常返回还是异常/中断，都必须解锁，否则后续所有线程永久阻塞
            lock.unlock();
        }
    }

    /**
     * 出队一个元素；队列空时阻塞等待，直到有生产者放入元素。
     *
     * @return 队首元素，按入队顺序返回
     * @throws InterruptedException 等待期间线程被中断时抛出，中断标记会被清除
     */
    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                // 同上：高频阻塞点，打日志会改变唤醒时序
                notEmpty.await();
            }
            E value = (E) items[takeIndex];
            // 清空槽位：让已出队的对象可以被 GC 回收，否则缓冲区会一直强引用它们造成内存泄漏
            items[takeIndex] = null;
            if (++takeIndex == items.length) {
                takeIndex = 0;
            }
            count--;
            notFull.signal();
            return value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 当前元素个数，仅用于观察和断言。
     *
     * @return 缓冲区中的元素数量
     */
    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 缓冲区容量。
     *
     * @return 构造时传入的容量，恒定不变
     */
    public int capacity() {
        // items.length 是 final 字段，天然不可变，不需要加锁
        return items.length;
    }
}
