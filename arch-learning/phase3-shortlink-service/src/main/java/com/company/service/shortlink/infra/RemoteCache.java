package com.company.service.shortlink.infra;

import java.time.Duration;
import java.util.Optional;

/**
 * L2 缓存（跨实例共享）的抽象。
 *
 * <p>【解决什么问题】跳转链路的第二层防线。L1 是进程内缓存，装不下所有热点、实例间也不同步；
 * 没有共享 L2，L1 每次未命中都会直接回源数据库，缓存击穿时数据库会被瞬间打满。
 *
 * <p>【为什么这样设计】把它抽成接口而不是直接依赖 Redis：
 * <ul>
 *   <li>单测可以完全不启动 Redis，用 {@link MemoryRemoteCache} 保持同一套语义；</li>
 *   <li>上层 {@code ShortlinkCache} 只关心"命中/未命中/写入/失效"，
 *       不关心底层是 Redis 还是别的共享存储；</li>
 *   <li>{@link #provider()} 让统计接口能说明当前跑的是哪种实现，便于排查配置问题。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】四个方法就是缓存的完整生命周期：读、写、失效、自报实现名。
 * 实现必须线程安全。
 */
public interface RemoteCache {

    /**
     * 读取缓存条目。
     *
     * @param code 短码
     * @return 命中时返回条目（可能是否定缓存），未命中返回空 Optional
     */
    Optional<CacheEntry> get(String code);

    /**
     * 写入缓存条目。
     *
     * @param code  短码
     * @param entry 条目，负缓存也通过这里写入
     * @param ttl   存活时间，由上层按条目类型决定
     */
    void put(String code, CacheEntry entry, Duration ttl);

    /**
     * 主动失效一个 key。
     *
     * @param code 短码
     */
    void evict(String code);

    /**
     * @return 实现名（如 "memory"、"redis"），仅用于展示与排查
     */
    String provider();
}
