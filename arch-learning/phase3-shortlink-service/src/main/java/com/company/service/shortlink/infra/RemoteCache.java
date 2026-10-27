package com.company.service.shortlink.infra;

import java.time.Duration;
import java.util.Optional;

/**
 * L2 缓存（跨实例共享）。
 *
 * <p>两种实现：本地 Map 模拟、Redis 真实共享。
 * 业务层只依赖这个接口，因此在单测里可以完全不启动 Redis。
 */
public interface RemoteCache {

    Optional<CacheEntry> get(String code);

    void put(String code, CacheEntry entry, Duration ttl);

    void evict(String code);

    String provider();
}
