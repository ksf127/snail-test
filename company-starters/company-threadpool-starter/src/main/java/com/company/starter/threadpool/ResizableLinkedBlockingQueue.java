package com.company.starter.threadpool;

import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 容量可热更新的有界阻塞队列。
 *
 * <p>{@link java.util.concurrent.LinkedBlockingQueue} 与
 * {@link java.util.concurrent.ArrayBlockingQueue} 的容量都是 final 字段，
 * 而 {@link java.util.concurrent.ThreadPoolExecutor#getQueue()} 返回的队列又会被线程池长期持有，
 * 所以"运行时调大队列"这件事只能靠自研队列，不能靠替换队列引用。
 *
 * <p>缩容是"软缩容"：已排队任务不会被丢弃，只是新任务要等到 size 低于新容量才能入队。
 */
public class ResizableLinkedBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E> {

    private final int maxCapacity;

    private volatile int capacity;

    private final Deque<E> items = new ArrayDeque<>();

    private final ReentrantLock lock = new ReentrantLock();

    private final Condition notEmpty = lock.newCondition();

    private final Condition notFull = lock.newCondition();

    public ResizableLinkedBlockingQueue(int initialCapacity, int maxCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity 必须大于 0");
        }
        if (maxCapacity < initialCapacity) {
            throw new IllegalArgumentException("maxCapacity 不能小于 initialCapacity");
        }
        this.capacity = initialCapacity;
        this.maxCapacity = maxCapacity;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }

    /**
     * 运行期调整容量。
     *
     * @return 调整前的容量
     */
    public int setCapacity(int newCapacity) {
        if (newCapacity <= 0 || newCapacity > maxCapacity) {
            throw new IllegalArgumentException("capacity 必须落在 [1, " + maxCapacity + "]");
        }
        lock.lock();
        try {
            int old = capacity;
            capacity = newCapacity;
            if (newCapacity > old) {
                // 扩容后原本被阻塞的生产者可能已经可以入队
                notFull.signalAll();
            }
            return old;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return items.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int remainingCapacity() {
        lock.lock();
        try {
            return Math.max(0, capacity - items.size());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean offer(E e) {
        Objects.requireNonNull(e);
        lock.lock();
        try {
            if (items.size() >= capacity) {
                return false;
            }
            items.addLast(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        lock.lockInterruptibly();
        try {
            while (items.size() >= capacity) {
                notFull.await();
            }
            items.addLast(e);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(e);
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (items.size() >= capacity) {
                if (nanos <= 0L) {
                    return false;
                }
                nanos = notFull.awaitNanos(nanos);
            }
            items.addLast(e);
            notEmpty.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public E poll() {
        lock.lock();
        try {
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (items.isEmpty()) {
                notEmpty.await();
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (items.isEmpty()) {
                if (nanos <= 0L) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    private E dequeue() {
        E value = items.pollFirst();
        if (value != null) {
            notFull.signal();
        }
        return value;
    }

    @Override
    public E peek() {
        lock.lock();
        try {
            return items.peekFirst();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(Collection<? super E> c, int maxElements) {
        Objects.requireNonNull(c);
        if (c == this) {
            throw new IllegalArgumentException("不能把队列 drain 到自身");
        }
        if (maxElements <= 0) {
            return 0;
        }
        lock.lock();
        try {
            int drained = 0;
            while (drained < maxElements && !items.isEmpty()) {
                c.add(items.pollFirst());
                drained++;
            }
            if (drained > 0) {
                notFull.signalAll();
            }
            return drained;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean remove(Object o) {
        lock.lock();
        try {
            boolean removed = items.remove(o);
            if (removed) {
                notFull.signal();
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean contains(Object o) {
        lock.lock();
        try {
            return items.contains(o);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            int removed = items.size();
            items.clear();
            if (removed > 0) {
                notFull.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回快照迭代器：只用于监控/排障，不做并发语义承诺（ThreadPoolExecutor 不使用 iterator）。
     */
    @Override
    public Iterator<E> iterator() {
        lock.lock();
        try {
            return new ArrayList<>(items).iterator();
        } finally {
            lock.unlock();
        }
    }
}
