package com.company.service.shortlink.infra;

import com.company.service.shortlink.domain.Shortlink;

/**
 * 缓存里存的对象。
 *
 * <p>为什么不能直接用 null 表示"不存在"：
 * RedisCacheService 对 null 会写空值哨兵，读回来还是 null，
 * 业务无法区分"没缓过"和"缓过但不存在"，负缓存的命中率就无从统计。
 * 这里显式带 {@code negative} 标记，让两种情况在类型上就分开。
 */
public record CacheEntry(Shortlink link, boolean negative) {

    public static CacheEntry of(Shortlink link) {
        return new CacheEntry(link, false);
    }

    public static CacheEntry absent() {
        return new CacheEntry(null, true);
    }
}
