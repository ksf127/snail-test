package com.company.service.shortlink.infra;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存点击存储，单测与无 Redis 环境使用。
 *
 * <p>entry 不做删除：删除与并发自增之间存在"删掉后又有一次自增落在已移除的计数器上"的丢计数窗口。
 * 短码数量本身有界，保留 entry 的代价远小于丢计数。
 */
public class MemoryClickStore implements ClickStore {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public void increment(String code, long delta) {
        counters.computeIfAbsent(code, key -> new AtomicLong()).addAndGet(delta);
    }

    @Override
    public long clicks(String code) {
        AtomicLong counter = counters.get(code);
        return counter == null ? 0 : counter.get();
    }

    public int trackedCodes() {
        return counters.size();
    }
}
