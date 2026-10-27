package com.company.service.archlab.cache;

import java.util.concurrent.atomic.LongAdder;

/**
 * 缓存命中统计：每个实验结束都能给出"L1 命中 / L2 命中 / 回源 / 空值命中"四个数，
 * 没有这四个数，多级缓存就只是在"感觉变快了"。
 */
public final class CacheCounters {

    public record Snapshot(long l1Hits, long l2Hits, long nullHits, long loads) {

        public long total() {
            return l1Hits + l2Hits + loads;
        }

        public double l1HitRate() {
            long total = total();
            return total == 0 ? 0 : (double) l1Hits / total;
        }

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

    public Snapshot snapshot() {
        return new Snapshot(l1Hits.sum(), l2Hits.sum(), nullHits.sum(), loads.sum());
    }
}
