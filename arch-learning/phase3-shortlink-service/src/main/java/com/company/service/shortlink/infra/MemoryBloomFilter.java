package com.company.service.shortlink.infra;

import com.company.service.shortlink.domain.BloomFilter;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 内存布隆过滤器：位图 + 原子位操作。
 *
 * <p>1 亿条、误判率 1e-4 时大约占 240MB，因此真实环境用 Redis 位图分摊到服务端；
 * 这里保留一份内存实现，是为了单测能在毫秒级验证"假阳性有上界、假阴性为零"。
 */
public class MemoryBloomFilter implements BloomFilter {

    private final long bitSize;

    private final int hashCount;

    private final long expectedInsertions;

    private final double falsePositiveRate;

    private final AtomicLongArray words;

    private final AtomicLong added = new AtomicLong();

    public MemoryBloomFilter(long expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions 必须大于 0: " + expectedInsertions);
        }
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("falsePositiveRate 必须落在 (0,1): " + falsePositiveRate);
        }
        this.expectedInsertions = expectedInsertions;
        this.falsePositiveRate = falsePositiveRate;
        this.bitSize = BloomMath.bitSize(expectedInsertions, falsePositiveRate);
        this.hashCount = BloomMath.hashCount(bitSize, expectedInsertions);
        this.words = new AtomicLongArray((int) ((bitSize + 63) >>> 6));
    }

    @Override
    public boolean mightContain(String key) {
        for (long position : BloomMath.positions(key, bitSize, hashCount)) {
            if (!isSet(position)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void add(String key) {
        for (long position : BloomMath.positions(key, bitSize, hashCount)) {
            set(position);
        }
        added.incrementAndGet();
    }

    private boolean isSet(long position) {
        long mask = 1L << (position & 63);
        return (words.get((int) (position >>> 6)) & mask) != 0;
    }

    private void set(long position) {
        long mask = 1L << (position & 63);
        int wordIndex = (int) (position >>> 6);
        while (true) {
            long current = words.get(wordIndex);
            if ((current & mask) != 0) {
                return;
            }
            if (words.compareAndSet(wordIndex, current, current | mask)) {
                return;
            }
        }
    }

    public long bitSize() {
        return bitSize;
    }

    public int hashCount() {
        return hashCount;
    }

    public long addedCount() {
        return added.get();
    }

    public long memoryBytes() {
        return (long) words.length() * Long.BYTES;
    }

    @Override
    public String toString() {
        return "MemoryBloomFilter{n=" + expectedInsertions
                + ", p=" + falsePositiveRate
                + ", bits=" + bitSize
                + ", k=" + hashCount
                + ", memory=" + memoryBytes() + "B}";
    }
}
