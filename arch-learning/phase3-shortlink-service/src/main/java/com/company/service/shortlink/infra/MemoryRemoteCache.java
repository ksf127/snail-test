package com.company.service.shortlink.infra;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 本地 Map 版 L2：只为在没有 Redis 的环境里保持同一套语义。
 *
 * <p>不要在生产多实例部署时用它 —— 实例之间的失效互相看不见。
 */
public class MemoryRemoteCache implements RemoteCache {

    private record Slot(CacheEntry entry, long expireAtNanos) {
    }

    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    private final LongSupplier ticker;

    public MemoryRemoteCache() {
        this(System::nanoTime);
    }

    /**
     * 注入可控时钟，单测里可以精确验证 TTL 过期，而不用 sleep。
     */
    public MemoryRemoteCache(LongSupplier ticker) {
        this.ticker = ticker;
    }

    @Override
    public Optional<CacheEntry> get(String code) {
        Slot slot = slots.get(code);
        if (slot == null) {
            return Optional.empty();
        }
        if (ticker.getAsLong() >= slot.expireAtNanos()) {
            slots.remove(code, slot);
            return Optional.empty();
        }
        return Optional.of(slot.entry());
    }

    @Override
    public void put(String code, CacheEntry entry, Duration ttl) {
        slots.put(code, new Slot(entry, ticker.getAsLong() + ttl.toNanos()));
    }

    @Override
    public void evict(String code) {
        slots.remove(code);
    }

    @Override
    public String provider() {
        return "memory";
    }

    public int size() {
        return slots.size();
    }
}
