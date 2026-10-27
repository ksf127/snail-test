package com.company.service.archlab.cache;

import java.time.Duration;

/**
 * 二级缓存抽象：只存字符串，序列化与空值策略交给上层。
 *
 * <p>抽出接口的意义不只是"换 Redis 方便"，而是让实验可以脱离中间件跑：
 * 单测用内存实现验证逻辑，联调/压测再切到 Redis 实现。
 */
public interface RemoteCache {

    /** @return 未命中返回 {@code null}，命中空值哨兵时返回哨兵字符串 */
    String get(String key);

    void put(String key, String value, Duration ttl);

    void evict(String key);
}
