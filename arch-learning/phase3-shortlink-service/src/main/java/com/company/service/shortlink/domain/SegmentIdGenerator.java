package com.company.service.shortlink.domain;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 双缓冲号段发号器。
 *
 * <p>结构：内存里始终持有一段 [cursor, limit) 的号码，取号只做一次 CAS；
 * 剩余量低于阈值时异步把下一段拉回来放进 {@code prefetched}，把"取号"和"网络往返"解耦。
 *
 * <p>三条必须守住的性质：
 * <ol>
 *   <li><b>绝不重复</b>：所有 {@link SegmentSource#nextRange(int)} 调用都在同一把锁内串行，
 *       保证拿到的号段单调递增，因此预取与同步取不会出现"回退到更小号段"</li>
 *   <li><b>耗尽才切换</b>：切换发生在 cursor 越过 limit 之后，切换期间新来的线程要么在锁外自旋，
 *       要么进入锁内复查，不会取到旧段的号</li>
 *   <li><b>允许浪费</b>：进程重启会丢掉未用完的号段；异步预取拿到的段也可能被丢弃。
 *       浪费只是少用几个号，重复才是事故</li>
 * </ol>
 */
public class SegmentIdGenerator implements IdGenerator {

    private static final Logger log = LoggerFactory.getLogger(SegmentIdGenerator.class);

    private final SegmentSource source;

    private final int step;

    private final double prefetchThreshold;

    private final Executor executor;

    /**
     * 号段切换锁：只在"取号段"时争用，正常取号路径完全不经过它。
     */
    private final Object rotateLock = new Object();

    private final AtomicLong cursor = new AtomicLong(0);

    private volatile long limit;

    private volatile Range prefetched;

    private final AtomicInteger prefetching = new AtomicInteger();

    private final LongAdder sourceCalls = new LongAdder();

    private final LongAdder prefetchedSegments = new LongAdder();

    public SegmentIdGenerator(SegmentSource source, int step, double prefetchThreshold, Executor executor) {
        if (source == null) {
            throw new IllegalArgumentException("SegmentSource 不能为空");
        }
        if (step <= 0) {
            throw new IllegalArgumentException("step 必须大于 0: " + step);
        }
        if (prefetchThreshold < 0 || prefetchThreshold > 1) {
            throw new IllegalArgumentException("prefetchThreshold 必须落在 [0,1]: " + prefetchThreshold);
        }
        this.source = source;
        this.step = step;
        this.prefetchThreshold = prefetchThreshold;
        this.executor = executor == null ? Runnable::run : executor;
    }

    @Override
    public long nextId() {
        while (true) {
            long id = cursor.get();
            if (id < limit) {
                if (cursor.compareAndSet(id, id + 1)) {
                    maybePrefetch(id + 1);
                    return id;
                }
                continue;
            }
            rotate();
        }
    }

    /**
     * cursor 已越过 limit：串行切换号段。
     */
    private void rotate() {
        synchronized (rotateLock) {
            if (cursor.get() < limit) {
                // 别的线程已经切好了，直接回去取号
                return;
            }
            // 必须在锁内复查 prefetched：异步预取也是在锁内写入的
            if (prefetched == null) {
                prefetched = fetch();
            }
            Range next = prefetched;
            prefetched = null;
            cursor.set(next.startInclusive());
            limit = next.endExclusive();
        }
    }

    private Range fetch() {
        sourceCalls.increment();
        Range range = source.nextRange(step);
        if (range == null || range.size() != step) {
            throw new IllegalStateException("号段来源返回了非法号段: " + range + "，期望长度 " + step);
        }
        return range;
    }

    private void maybePrefetch(long nextCursor) {
        if (prefetchThreshold <= 0) {
            return;
        }
        long threshold = Math.max(1, (long) Math.ceil(step * prefetchThreshold));
        long remaining = limit - nextCursor;
        if (remaining >= threshold || prefetched != null) {
            return;
        }
        if (!prefetching.compareAndSet(0, 1)) {
            return;
        }
        try {
            executor.execute(this::doPrefetch);
        } catch (RuntimeException e) {
            prefetching.set(0);
            log.warn("号段预取任务提交失败，将由取号线程同步兜底", e);
        }
    }

    private void doPrefetch() {
        try {
            synchronized (rotateLock) {
                Range range = fetch();
                prefetchedSegments.increment();
                if (prefetched == null) {
                    prefetched = range;
                } else {
                    // 同步路径已经补上了号段；丢弃后拿到的这一段（更靠后的号段），只会浪费不会重复
                    log.debug("丢弃重复预取号段: {}", range);
                }
            }
        } catch (RuntimeException e) {
            log.warn("号段预取失败，后续取号会走同步路径", e);
        } finally {
            prefetching.set(0);
        }
    }

    /**
     * 号段来源被调用次数，用于验证"预取是否真的把往返次数降下来了"。
     */
    public long sourceCalls() {
        return sourceCalls.sum();
    }

    public long prefetchedSegments() {
        return prefetchedSegments.sum();
    }

    public long remainingInSegment() {
        return limit - cursor.get();
    }

    public int step() {
        return step;
    }
}
