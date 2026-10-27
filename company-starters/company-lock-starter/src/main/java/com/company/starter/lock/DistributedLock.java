package com.company.starter.lock;

import java.time.Duration;

/**
 * 分布式锁统一入口。
 *
 * <p>三种实现共用同一套语义，便于在同一个场景里做对照：
 * <ul>
 *   <li>{@code memory}：单 JVM 内有效，默认实现，CI 与单测零外部依赖</li>
 *   <li>{@code redis}：SET NX PX + Lua 原子释放 + 看门狗续期 + 可重入</li>
 *   <li>{@code redisson}：成熟实现，用来验证自研版本漏掉了哪些细节</li>
 * </ul>
 *
 * <p>注意：内存实现在多实例部署下不成立，它存在的意义是"把分布式问题退化成单机问题"，
 * 让并发缺陷在没有中间件时也能被复现和测试。
 */
public interface DistributedLock {

    /**
     * 尝试加锁，不等待。成功返回句柄，失败返回 null。
     *
     * @param leaseTime 租期；实现方负责在业务未结束时续期（看门狗），
     *                  进程崩溃时靠这个时间自动释放
     */
    LockHandle tryLock(String key, Duration leaseTime);

    /**
     * 在 waitTime 内不断重试，超时返回 null。
     * 采用指数退避（5ms → 50ms），避免大量线程同时打 Redis。
     */
    default LockHandle lock(String key, Duration waitTime, Duration leaseTime) {
        long deadline = System.nanoTime() + waitTime.toNanos();
        long backoffMillis = 5;
        while (true) {
            LockHandle handle = tryLock(key, leaseTime);
            if (handle != null) {
                return handle;
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(backoffMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            backoffMillis = Math.min(backoffMillis * 2, 50);
        }
    }

    /**
     * 实现标识，用于把实验数据和实现方式对上号。
     */
    String provider();
}
