package com.company.service.archlab.concurrency;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 手写有界缓冲区（生产者/消费者），用 {@link Condition} 而不是 wait/notify。
 *
 * <p>为什么不用 wait/notify：
 * <ul>
 *   <li>wait/notify 绑定在对象监视器上，一个对象只能有一个等待队列，
 *       生产者与消费者会互相唤醒（惊群），必须靠 notifyAll 兜底；</li>
 *   <li>Condition 可以按"队列不满 / 队列不空"拆成两个等待队列，
 *       {@code signal()} 就只唤醒对方阵营，语义清晰且开销更小。</li>
 * </ul>
 *
 * <p>注意 while 而不是 if：被唤醒不代表条件成立（可能是虚假唤醒、也可能被别人先抢走），
 * 必须重新判断，这是并发代码最高频的 Bug 来源之一。
 */
public final class BoundedBuffer<E> {

    private final Object[] items;

    private int putIndex;

    private int takeIndex;

    private int count;

    private final ReentrantLock lock = new ReentrantLock();

    private final Condition notFull = lock.newCondition();

    private final Condition notEmpty = lock.newCondition();

    public BoundedBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须大于 0");
        }
        this.items = new Object[capacity];
    }

    public void put(E value) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == items.length) {
                notFull.await();
            }
            items[putIndex] = value;
            if (++putIndex == items.length) {
                putIndex = 0;
            }
            count++;
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (count == 0) {
                notEmpty.await();
            }
            E value = (E) items[takeIndex];
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

    public int size() {
        lock.lock();
        try {
            return count;
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return items.length;
    }
}
