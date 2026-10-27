package com.company.starter.lock;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单 JVM 内的分布式锁替身，默认实现。
 *
 * <p>它刻意保留了分布式锁的三个关键语义，方便本地复现问题：
 * <ul>
 *   <li>可重入：同一线程重复加锁只增计数</li>
 *   <li>租期：超过 leaseTime 后其他线程可以抢占（没有看门狗）</li>
 *   <li>持有者校验：过期被抢占后，旧持有者释放不会误删新持有者的锁</li>
 * </ul>
 *
 * <p>它做不到的事同样重要：多实例之间互斥、进程崩溃后自动释放（进程没了内存状态也没了）。
 */
public class InMemoryDistributedLock implements DistributedLock {

    private final String keyPrefix;
    private final ConcurrentHashMap<String, Entry> locks = new ConcurrentHashMap<>();
    private final AtomicLong ownerSequence = new AtomicLong();
    /**
     * 用一个 JVM 内自增的 ownerId 标识线程。不能用线程名/id：
     * 线程池复用线程时同一个线程确实应该重入，但两个同名线程会互相误判。
     */
    private final ThreadLocal<Long> ownerId = ThreadLocal.withInitial(ownerSequence::incrementAndGet);

    public InMemoryDistributedLock(String keyPrefix) {
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }

    @Override
    public LockHandle tryLock(String key, Duration leaseTime) {
        String realKey = keyPrefix + key;
        String owner = String.valueOf(ownerId.get());
        long expireAt = System.nanoTime() + leaseTime.toNanos();
        AtomicBoolean acquired = new AtomicBoolean(false);

        locks.compute(realKey, (k, entry) -> {
            long now = System.nanoTime();
            if (entry == null || entry.isExpired(now)) {
                acquired.set(true);
                return new Entry(owner, 1, expireAt);
            }
            if (entry.owner.equals(owner)) {
                acquired.set(true);
                entry.count++;
                entry.expireAtNanos = expireAt;
                return entry;
            }
            return entry;
        });

        return acquired.get() ? new MemoryLockHandle(realKey, owner) : null;
    }

    @Override
    public String provider() {
        return "memory";
    }

    /**
     * 仅供实验观测：当前仍在锁表里的 key 数量。
     */
    public int size() {
        return locks.size();
    }

    private static final class Entry {

        private final String owner;

        private int count;

        private long expireAtNanos;

        private Entry(String owner, int count, long expireAtNanos) {
            this.owner = owner;
            this.count = count;
            this.expireAtNanos = expireAtNanos;
        }

        private boolean isExpired(long now) {
            return now >= expireAtNanos;
        }
    }

    private final class MemoryLockHandle implements LockHandle {

        private final String realKey;

        private final String owner;

        private final AtomicBoolean released = new AtomicBoolean(false);

        private MemoryLockHandle(String realKey, String owner) {
            this.realKey = realKey;
            this.owner = owner;
        }

        @Override
        public String key() {
            return realKey;
        }

        @Override
        public String token() {
            return owner;
        }

        @Override
        public boolean isHeld() {
            return !released.get();
        }

        @Override
        public void unlock() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            locks.compute(realKey, (k, entry) -> {
                // 锁已被别人抢走（自己过期），不能删别人的
                if (entry == null || !entry.owner.equals(owner)) {
                    return entry;
                }
                if (entry.count > 1) {
                    entry.count--;
                    return entry;
                }
                return null;
            });
        }
    }
}
