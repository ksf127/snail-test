package com.company.service.archlab.concurrency;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

/**
 * 可见性实验：一个线程写、另一个线程读，看读线程多久能看到。
 *
 * <p>这是 JMM 最容易被忽略的地方：{@code volatile} 保证可见性与有序性，不保证原子性。
 * 不加 volatile 时，读线程可能永远读到旧值（循环被 JIT 提升到寄存器），
 * 也可能"碰巧"读到新值 —— 因为 x86 的缓存一致性协议比 JMM 允许的更强，
 * 这正是"本地跑不出来、线上一改就炸"的经典来源。
 *
 * <p>实验带超时：读线程最多自旋 timeoutMillis，不会把请求线程挂死。
 */
@Service
public class VisibilityDemo {

    public record Result(boolean useVolatile, boolean observed, long elapsedMillis, long polls) {
    }

    public Result run(boolean useVolatile, long timeoutMillis) {
        if (timeoutMillis <= 0 || timeoutMillis > 10_000) {
            throw new IllegalArgumentException("timeoutMillis 必须落在 [1, 10000]");
        }
        SharedFlag flag = useVolatile ? new VolatileFlag() : new PlainFlag();
        Thread writer = new Thread(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            flag.set();
        }, "visibility-writer");
        writer.setDaemon(true);
        writer.start();

        long begin = System.nanoTime();
        long deadline = begin + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long polls = 0L;
        while (!flag.get()) {
            polls++;
            if (System.nanoTime() >= deadline) {
                return new Result(useVolatile, false, timeoutMillis, polls);
            }
            Thread.onSpinWait();
        }
        long elapsedMillis = Math.max(1L, (System.nanoTime() - begin) / 1_000_000L);
        return new Result(useVolatile, true, elapsedMillis, polls);
    }

    private interface SharedFlag {

        void set();

        boolean get();
    }

    private static final class PlainFlag implements SharedFlag {

        private boolean value;

        @Override
        public void set() {
            value = true;
        }

        @Override
        public boolean get() {
            return value;
        }
    }

    private static final class VolatileFlag implements SharedFlag {

        private volatile boolean value;

        @Override
        public void set() {
            value = true;
        }

        @Override
        public boolean get() {
            return value;
        }
    }
}
