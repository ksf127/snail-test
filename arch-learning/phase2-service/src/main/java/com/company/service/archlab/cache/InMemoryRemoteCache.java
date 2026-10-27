package com.company.service.archlab.cache;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 内存版 L2：用来在没有 Redis 的环境里完整跑通实验。
 *
 * <p>它不是替代品，只是"接口可替换"的证明：把 {@code arch-lab.cache.remote}
 * 改成 {@code redis} 后，业务代码一行都不用改。
 */
@Component
@ConditionalOnProperty(name = "arch-lab.cache.remote", havingValue = "memory", matchIfMissing = true)
public class InMemoryRemoteCache implements RemoteCache {

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    @Override
    public String get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expireAtMillis() <= System.currentTimeMillis()) {
            store.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        long expireAt = ttl == null || ttl.isZero() || ttl.isNegative()
                ? Long.MAX_VALUE
                : System.currentTimeMillis() + ttl.toMillis();
        store.put(key, new Entry(value, expireAt));
    }

    @Override
    public void evict(String key) {
        store.remove(key);
    }

    public void clear() {
        store.clear();
    }

    public int size() {
        return store.size();
    }

    private record Entry(String value, long expireAtMillis) {
    }
}
