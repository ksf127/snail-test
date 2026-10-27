package com.company.starter.threadpool;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 支持运行时调整 core / max / 队列容量 / 拒绝策略的线程池。
 *
 * <p>JDK 原生只提供了 {@code setCorePoolSize} 与 {@code setMaximumPoolSize}，
 * 队列容量没有 setter，所以这里组合了 {@link ResizableLinkedBlockingQueue}。
 *
 * <p>调整顺序有讲究：
 * <ul>
 *   <li>调大容量时先抬 max 再抬 core，否则 core &gt; max 会抛 IllegalArgumentException；</li>
 *   <li>调小容量时先降 core 再降 max，同样是为了绕开 core &gt; max 的校验。</li>
 * </ul>
 */
public class ResizableThreadPoolExecutor extends ThreadPoolExecutor {

    private final String poolName;

    private final ResizableLinkedBlockingQueue<Runnable> workQueue;

    private final RejectionCounter rejectionCounter;

    private ResizableThreadPoolExecutor(String poolName,
                                       int corePoolSize,
                                       int maxPoolSize,
                                       ResizableLinkedBlockingQueue<Runnable> workQueue,
                                       Duration keepAlive,
                                       RejectionCounter rejectionCounter,
                                       boolean allowCoreThreadTimeOut) {
        super(corePoolSize, maxPoolSize, keepAlive.toMillis(), TimeUnit.MILLISECONDS,
                workQueue, new CompanyThreadFactory(poolName), rejectionCounter);
        this.poolName = poolName;
        this.workQueue = workQueue;
        this.rejectionCounter = rejectionCounter;
        if (allowCoreThreadTimeOut) {
            allowCoreThreadTimeOut(true);
        }
    }

    public static ResizableThreadPoolExecutor create(String poolName,
                                                     int corePoolSize,
                                                     int maxPoolSize,
                                                     int queueCapacity,
                                                     int maxQueueCapacity,
                                                     Duration keepAlive,
                                                     RejectedPolicy rejectedPolicy,
                                                     boolean allowCoreThreadTimeOut) {
        if (corePoolSize < 0 || maxPoolSize <= 0 || corePoolSize > maxPoolSize) {
            throw new IllegalArgumentException("线程池 " + poolName + " 参数非法: core=" + corePoolSize + ", max=" + maxPoolSize);
        }
        int effectiveMaxQueue = Math.max(maxQueueCapacity, queueCapacity);
        ResizableLinkedBlockingQueue<Runnable> queue = new ResizableLinkedBlockingQueue<>(queueCapacity, effectiveMaxQueue);
        RejectionCounter counter = new RejectionCounter(rejectedPolicy.handler());
        return new ResizableThreadPoolExecutor(poolName, corePoolSize, maxPoolSize, queue,
                keepAlive, counter, allowCoreThreadTimeOut);
    }

    public String getPoolName() {
        return poolName;
    }

    @Override
    public ResizableLinkedBlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    public long getRejectedCount() {
        return rejectionCounter.count();
    }

    public int getQueueCapacity() {
        return workQueue.getCapacity();
    }

    /**
     * 一次性调整三个维度。任何一步失败都抛出异常，由调用方决定是否回滚（配置中心场景通常是忽略本次变更）。
     */
    public synchronized void resize(int corePoolSize, int maxPoolSize, int queueCapacity) {
        if (corePoolSize < 0 || maxPoolSize <= 0 || corePoolSize > maxPoolSize) {
            throw new IllegalArgumentException("线程池 " + poolName + " 参数非法: core=" + corePoolSize + ", max=" + maxPoolSize);
        }
        int currentMax = getMaximumPoolSize();
        if (maxPoolSize > currentMax) {
            setMaximumPoolSize(maxPoolSize);
            setCorePoolSize(corePoolSize);
        } else {
            setCorePoolSize(corePoolSize);
            setMaximumPoolSize(maxPoolSize);
        }
        workQueue.setCapacity(queueCapacity);
    }

    public synchronized void changeRejectedPolicy(RejectedPolicy policy) {
        rejectionCounter.setDelegate(policy.handler());
    }

    /**
     * 包一层计数后再委托，拒绝次数才能作为指标暴露；直接 setRejectedExecutionHandler 会丢掉计数。
     */
    private static final class RejectionCounter implements RejectedExecutionHandler {

        private final AtomicLong count = new AtomicLong();

        private volatile RejectedExecutionHandler delegate;

        private RejectionCounter(RejectedExecutionHandler delegate) {
            this.delegate = delegate;
        }

        private void setDelegate(RejectedExecutionHandler delegate) {
            this.delegate = delegate;
        }

        private long count() {
            return count.get();
        }

        @Override
        public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
            count.incrementAndGet();
            delegate.rejectedExecution(runnable, executor);
        }
    }
}
