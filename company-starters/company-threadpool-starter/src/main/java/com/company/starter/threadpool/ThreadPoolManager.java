package com.company.starter.threadpool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * 线程池注册表：所有线程池由容器统一创建、统一暴露指标、统一优雅停机。
 *
 * <p>为什么不让业务自己 new ThreadPoolExecutor：
 * 没有名字、没有指标、没有停机等待，线上只能靠 jstack 猜。
 */
public class ThreadPoolManager implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolManager.class);

    private final CompanyThreadPoolProperties properties;

    private final MeterRegistry meterRegistry;

    private final Map<String, ResizableThreadPoolExecutor> executors = new ConcurrentHashMap<>();

    public ThreadPoolManager(CompanyThreadPoolProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.getExecutors() == null) {
            return;
        }
        properties.getExecutors().forEach(this::create);
    }

    /**
     * 按配置创建线程池；同名重复创建直接失败，避免线程泄漏。
     */
    public ResizableThreadPoolExecutor create(String name, CompanyThreadPoolProperties.ExecutorProperties config) {
        if (executors.containsKey(name)) {
            throw new IllegalStateException("线程池已存在: " + name);
        }
        ResizableThreadPoolExecutor executor = ResizableThreadPoolExecutor.create(
                name,
                config.getCorePoolSize(),
                config.getMaxPoolSize(),
                config.getQueueCapacity(),
                config.getMaxQueueCapacity(),
                config.getKeepAlive(),
                config.getRejectedPolicy(),
                config.isAllowCoreThreadTimeOut());
        executors.put(name, executor);
        registerMetrics(name, executor);
        log.info("线程池已创建: name={}, core={}, max={}, queue={}, policy={}",
                name, executor.getCorePoolSize(), executor.getMaximumPoolSize(),
                executor.getQueueCapacity(), config.getRejectedPolicy());
        return executor;
    }

    public ResizableThreadPoolExecutor get(String name) {
        ResizableThreadPoolExecutor executor = executors.get(name);
        if (executor == null) {
            throw new IllegalArgumentException("未找到线程池: " + name + "，已注册: " + executors.keySet());
        }
        return executor;
    }

    public boolean contains(String name) {
        return executors.containsKey(name);
    }

    public List<Snapshot> snapshot() {
        List<Snapshot> snapshots = new ArrayList<>(executors.size());
        executors.forEach((name, executor) -> snapshots.add(new Snapshot(
                name,
                executor.getCorePoolSize(),
                executor.getMaximumPoolSize(),
                executor.getPoolSize(),
                executor.getActiveCount(),
                executor.getQueue().size(),
                executor.getQueueCapacity(),
                executor.getCompletedTaskCount(),
                executor.getRejectedCount())));
        snapshots.sort(Comparator.comparing(Snapshot::name));
        return snapshots;
    }

    public void resize(String name, int corePoolSize, int maxPoolSize, int queueCapacity) {
        ResizableThreadPoolExecutor executor = get(name);
        executor.resize(corePoolSize, maxPoolSize, queueCapacity);
        log.info("线程池已调整: name={}, core={}, max={}, queue={}",
                name, corePoolSize, maxPoolSize, queueCapacity);
    }

    public void changeRejectedPolicy(String name, RejectedPolicy policy) {
        get(name).changeRejectedPolicy(policy);
        log.info("线程池拒绝策略已调整: name={}, policy={}", name, policy);
    }

    private void registerMetrics(String name, ResizableThreadPoolExecutor executor) {
        if (meterRegistry == null) {
            return;
        }
        Tags tags = Tags.of("pool", name);
        Gauge.builder("company.thread.pool.size", executor, ResizableThreadPoolExecutor::getPoolSize)
                .tags(tags).register(meterRegistry);
        Gauge.builder("company.thread.pool.active", executor, ResizableThreadPoolExecutor::getActiveCount)
                .tags(tags).register(meterRegistry);
        Gauge.builder("company.thread.pool.queue.size", executor, e -> e.getQueue().size())
                .tags(tags).register(meterRegistry);
        Gauge.builder("company.thread.pool.queue.remaining", executor, e -> e.getQueue().remainingCapacity())
                .tags(tags).register(meterRegistry);
        Gauge.builder("company.thread.pool.completed", executor, ResizableThreadPoolExecutor::getCompletedTaskCount)
                .tags(tags).register(meterRegistry);
        Gauge.builder("company.thread.pool.rejected", executor, ResizableThreadPoolExecutor::getRejectedCount)
                .tags(tags).register(meterRegistry);
    }

    @Override
    public void destroy() {
        Duration await = properties.getAwaitTermination();
        long deadline = System.nanoTime() + await.toNanos();
        executors.forEach((name, executor) -> executor.shutdown());
        executors.forEach((name, executor) -> {
            try {
                long remaining = deadline - System.nanoTime();
                boolean terminated = remaining > 0
                        && executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                if (!terminated) {
                    int dropped = executor.shutdownNow().size();
                    log.warn("线程池 {} 未在 {} 内退出，已强制中断，丢弃任务 {} 个", name, await, dropped);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        });
    }

    /**
     * 给 Actuator / 自定义端点用的只读快照。
     */
    public record Snapshot(String name,
                           int corePoolSize,
                           int maxPoolSize,
                           int poolSize,
                           int activeCount,
                           int queueSize,
                           int queueCapacity,
                           long completedTaskCount,
                           long rejectedCount) {
    }
}
