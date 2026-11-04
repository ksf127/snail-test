package com.company.service.shortlink.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 本地 Map 版 L2 缓存：只为在没有 Redis 的环境里保持同一套语义。
 *
 * <p>【解决什么问题】L2 的职责是"跨请求、跨实例共享的二级缓存"。
 * 这个实现让单测和本地启动不需要 Redis 就能跑通 L1 → L2 → 存储的完整回源链路，
 * 也能精确验证 TTL 与负缓存行为。
 *
 * <p>【为什么这样设计】
 * <ul>
 *   <li>把值和过期时间包成 {@code Slot}：TTL 是 L2 的核心语义，不能省；</li>
 *   <li>时钟通过 {@link LongSupplier} 注入：单测里可以用可控时钟验证过期，不用 sleep；</li>
 *   <li>过期采用"读时惰性删除"而不是后台线程：实现简单、无额外调度，
 *       代价是过期 key 在没人访问前仍占内存。真实 Redis 用惰性删除 + 定期抽样，思路一致。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】先看 {@link #get} 的三条分支（不存在 / 已过期 / 命中），
 * 再看 {@link #put} 如何把 ttl 换算成绝对过期时刻。
 * 不要在生产多实例部署时用它 —— 实例之间的失效互相看不见。
 * 基于 ConcurrentHashMap，所有方法线程安全。
 */
public class MemoryRemoteCache implements RemoteCache {

    private static final Logger log = LoggerFactory.getLogger(MemoryRemoteCache.class);

    /**
     * 缓存槽：值 + 绝对过期时刻（纳秒，来自注入的时钟）。
     *
     * @param entry         缓存条目（可能是负缓存）
     * @param expireAtNanos 到期时刻，用 ticker 的时基
     */
    private record Slot(CacheEntry entry, long expireAtNanos) {
    }

    /**
     * 短码 → 缓存槽。
     */
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    /**
     * 时钟源。默认用 {@link System#nanoTime()}：单调递增，不受系统时间调整影响。
     */
    private final LongSupplier ticker;

    public MemoryRemoteCache() {
        this(System::nanoTime);
        log.info("L2 缓存使用内存实现（仅限单机/测试；多实例部署请切换 shortlink.cache.remote=redis）");
    }

    /**
     * 注入可控时钟，单测里可以精确验证 TTL 过期，而不用 sleep。
     *
     * @param ticker 返回纳秒时间戳的时钟源
     */
    public MemoryRemoteCache(LongSupplier ticker) {
        this.ticker = ticker;
    }

    /**
     * 读取缓存。
     *
     * <p>先判断是否过期，再返回值：宁可多一次比较，也不让过期数据被业务读到。
     * {@code remove(code, slot)} 带值删除，避免误删同一 key 上刚写入的新值。
     *
     * @param code 短码
     * @return 命中且未过期时返回条目；不存在或已过期返回空
     */
    @Override
    public Optional<CacheEntry> get(String code) {
        Slot slot = slots.get(code);
        if (slot == null) {
            return Optional.empty();
        }
        if (ticker.getAsLong() >= slot.expireAtNanos()) {
            // 带值删除：如果这期间别的线程已经更新成新值，这里不会误删
            slots.remove(code, slot);
            log.debug("L2 内存缓存条目已过期并清除: code={}", code);
            return Optional.empty();
        }
        return Optional.of(slot.entry());
    }

    /**
     * 写入缓存。
     *
     * @param code  短码
     * @param entry 缓存条目，负缓存也走这里
     * @param ttl   存活时间，由上层按"正常/负缓存"分别传入
     */
    @Override
    public void put(String code, CacheEntry entry, Duration ttl) {
        // 存绝对到期时刻而不是剩余时长：读取时不需要记录写入时间
        slots.put(code, new Slot(entry, ticker.getAsLong() + ttl.toNanos()));
    }

    /**
     * 主动失效一条缓存（删除短链时调用）。
     *
     * @param code 短码
     */
    @Override
    public void evict(String code) {
        slots.remove(code);
    }

    /**
     * @return 固定返回 "memory"，用于在统计接口里区分当前用的是哪种 L2
     */
    @Override
    public String provider() {
        return "memory";
    }

    /**
     * @return 当前槽位数量（含尚未被惰性删除的过期槽）
     */
    public int size() {
        return slots.size();
    }
}
