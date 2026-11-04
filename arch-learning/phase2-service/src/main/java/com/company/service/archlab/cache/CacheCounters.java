package com.company.service.archlab.cache;

import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 缓存命中统计：每个实验结束都能给出"L1 命中 / L2 命中 / 回源 / 空值命中"四个数，
 * 没有这四个数，多级缓存就只是在"感觉变快了"。
 *
 * <p>【解决什么问题】多级缓存上线后最常见的争论是"到底有没有效果"。
 * 只看接口耗时会被网络、GC、机器负载干扰，只有命中率能说明缓存层级是否按预期工作：
 * L1 命中率高说明热点被本地挡住了；L2 命中率高说明 Redis 在兜底；
 * 回源次数高则说明缓存没覆盖住，数据库迟早被打满。
 *
 * <p>【为什么这样设计】用 {@link LongAdder} 而不是 {@link java.util.concurrent.atomic.AtomicLong}：
 * 缓存命中是高频写操作，LongAdder 把计数分散到多个 Cell 上，减少多核 CAS 争用。
 * 另外把"空值命中"单独记一笔——它同时属于 L1/L2 命中，
 * 所以 {@link Snapshot#total()} 不能再加它，否则命中率会被算重。
 *
 * <p>【怎么读这段代码】先看 {@link Snapshot} 的四个字段与命中率算法，
 * 再看四个自增方法分别被谁调用（全在 {@link MultiLevelProductCache} 的读路径上），
 * 最后看 {@link #snapshot()}：实验结束时取一次快照，避免边读边变导致分子分母不在同一时刻。
 */
public final class CacheCounters {

    private static final Logger log = LoggerFactory.getLogger(CacheCounters.class);

    /**
     * 某一时刻的计数快照。
     *
     * @param l1Hits   L1（本地 Caffeine）命中次数
     * @param l2Hits   L2（Redis/内存）命中次数
     * @param nullHits 命中"空值哨兵"的次数；它是 L1/L2 命中的子集，不重复计入 total
     * @param loads    回源数据库的次数
     */
    public record Snapshot(long l1Hits, long l2Hits, long nullHits, long loads) {

        /** 总请求数：空值命中已经含在 L1/L2 命中里，所以这里只加三项。 */
        public long total() {
            return l1Hits + l2Hits + loads;
        }

        /** L1 命中率，取值范围 [0,1]；没有任何请求时返回 0，避免除零。 */
        public double l1HitRate() {
            long total = total();
            return total == 0 ? 0 : (double) l1Hits / total;
        }

        /** L2 命中率，取值范围 [0,1]；没有任何请求时返回 0。 */
        public double l2HitRate() {
            long total = total();
            return total == 0 ? 0 : (double) l2Hits / total;
        }
    }

    private final LongAdder l1Hits = new LongAdder();

    private final LongAdder l2Hits = new LongAdder();

    private final LongAdder nullHits = new LongAdder();

    private final LongAdder loads = new LongAdder();

    void l1Hit() {
        l1Hits.increment();
    }

    void l2Hit() {
        l2Hits.increment();
    }

    void nullHit() {
        nullHits.increment();
    }

    void load() {
        loads.increment();
    }

    /**
     * 取当前计数的只读快照。
     *
     * @return 四个计数的瞬时值；由于各计数器分开读取，高并发下可能不是严格同一纳秒的切面
     */
    public Snapshot snapshot() {
        Snapshot snapshot = new Snapshot(l1Hits.sum(), l2Hits.sum(), nullHits.sum(), loads.sum());
        log.debug("【缓存计数】L1 命中={}，L2 命中={}，空值命中={}，回源={}",
                snapshot.l1Hits(), snapshot.l2Hits(), snapshot.nullHits(), snapshot.loads());
        return snapshot;
    }
}
