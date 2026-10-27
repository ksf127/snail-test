package com.company.starter.threadpool;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程命名统一走这里：排查线上问题时，线程名是唯一不需要额外上下文的现场信息。
 */
public class CompanyThreadFactory implements ThreadFactory {

    private final String prefix;

    private final boolean daemon;

    private final AtomicInteger sequence = new AtomicInteger(1);

    public CompanyThreadFactory(String poolName) {
        this(poolName, true);
    }

    public CompanyThreadFactory(String poolName, boolean daemon) {
        this.prefix = poolName + "-";
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + sequence.getAndIncrement());
        thread.setDaemon(daemon);
        return thread;
    }
}
