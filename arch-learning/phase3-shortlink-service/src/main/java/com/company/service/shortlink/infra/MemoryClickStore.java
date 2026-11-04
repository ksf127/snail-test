package com.company.service.shortlink.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存点击存储，单测与无 Redis 环境使用。
 *
 * <p>【解决什么问题】它承接 {@code ClickRecorder} 定时刷下来的增量批次，
 * 让点击统计在零中间件环境下也能工作。生产环境应换成 {@link RedisClickStore}，
 * 因为内存版重启即丢、且多实例之间无法汇总。
 *
 * <p>【为什么这样设计】用 ConcurrentHashMap&lt;String, AtomicLong&gt;：
 * <ul>
 *   <li>Map 只负责"按短码找计数器"，真正的累加在 AtomicLong 上，避免对 Map 加锁；</li>
 *   <li>{@code computeIfAbsent} 保证同一个短码只会创建一个计数器，不会出现两个线程各建一个、互相覆盖。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】{@link #increment} 是唯一的写路径，注意它是"先拿到计数器再原子加"，
 * 不是"读出值 + delta 再写回"。{@link #clicks} 读不到时返回 0。
 * entry 不做删除：删除与并发自增之间存在"删掉后又有一次自增落在已移除的计数器上"的丢计数窗口。
 * 短码数量本身有界，保留 entry 的代价远小于丢计数。
 * 整个类线程安全。
 */
public class MemoryClickStore implements ClickStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryClickStore.class);

    /**
     * 短码 → 计数器。计数器不删除，避免并发删除/自增导致丢计数。
     */
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /**
     * 累加某个短码的点击量。
     *
     * @param code  短码
     * @param delta 本次增量，通常大于 0
     */
    @Override
    public void increment(String code, long delta) {
        // computeIfAbsent 返回的是"这个 key 对应的唯一计数器"，多条线程会拿到同一个对象
        counters.computeIfAbsent(code, key -> new AtomicLong()).addAndGet(delta);
        log.debug("内存点击量累加: code={}, delta={}", code, delta);
    }

    /**
     * @param code 短码
     * @return 已累计的点击量，没有记录时返回 0
     */
    @Override
    public long clicks(String code) {
        AtomicLong counter = counters.get(code);
        return counter == null ? 0 : counter.get();
    }

    /**
     * @return 当前有计数记录的短码数量，可用于观察内存占用
     */
    public int trackedCodes() {
        return counters.size();
    }
}
