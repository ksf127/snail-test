package com.company.service.shortlink.service;

import com.company.service.shortlink.infra.ClickStore;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 点击计数聚合器。
 *
 * <p>为什么不在跳转线程里直接 HINCRBY：
 * 100 万 QPS 的跳转如果每个都打一次 Redis 写，Redis 立刻成为瓶颈，而且跳转延迟被写放大。
 * 这里的做法是"请求线程内只做一次内存自增，后台每秒批量落一次"，
 * 代价是最多丢失 1 个刷新周期的计数（进程被 kill -9 时），换来主链路零 IO。
 *
 * <p>为什么用 AtomicLong#getAndSet(0) 而不是 LongAdder#sumThenReset：
 * 后者并发调用会丢更新；getAndSet 是原子的，刷新期间新来的自增会落在下一轮。
 */
@Component
public class ClickRecorder {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    private final ClickStore clickStore;

    private final LongAdder recorded = new LongAdder();

    private final LongAdder flushed = new LongAdder();

    public ClickRecorder(ClickStore clickStore) {
        this.clickStore = clickStore;
    }

    public void record(String code) {
        counters.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
        recorded.increment();
    }

    @Scheduled(fixedDelayString = "${shortlink.click.flush-interval-millis:1000}")
    public void flush() {
        counters.forEach((code, counter) -> {
            long delta = counter.getAndSet(0);
            if (delta > 0) {
                clickStore.increment(code, delta);
                flushed.add(delta);
            }
        });
    }

    /**
     * 已落库点击量 + 尚未刷新的增量。
     */
    public long clicks(String code) {
        return clickStore.clicks(code) + pending(code);
    }

    public long pending(String code) {
        AtomicLong counter = counters.get(code);
        return counter == null ? 0 : counter.get();
    }

    public long pendingTotal() {
        long total = 0;
        for (AtomicLong counter : counters.values()) {
            total += counter.get();
        }
        return total;
    }

    public long recordedTotal() {
        return recorded.sum();
    }

    public long flushedTotal() {
        return flushed.sum();
    }
}
