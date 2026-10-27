package com.company.service.archlab.cache;

/**
 * 极简布隆过滤器：位图 + 双重哈希，用来挡住"查不存在 id"的穿透流量。
 *
 * <p>关键性质：<b>可能存在（有假阳性），一定不存在（无假阴性）</b>。
 * 所以它只能用来"提前拒绝"，不能用来"确认存在"——最终判断仍然要回到缓存/数据库。
 *
 * <p>生产里用 RedisBloom/Guava，但原理就是这几十行：
 * 位数组长度与哈希次数由预期元素数 n 和误判率 p 共同决定。
 */
public final class BloomFilter {

    private final long[] bits;

    private final int bitCount;

    private final int hashCount;

    public BloomFilter(int expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions 必须大于 0");
        }
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("falsePositiveRate 必须在 (0, 1) 之间");
        }
        double ln2 = Math.log(2);
        int rawBits = (int) Math.ceil(-expectedInsertions * Math.log(falsePositiveRate) / (ln2 * ln2));
        this.bitCount = Math.max(64, (rawBits + 63) / 64 * 64);
        this.hashCount = Math.max(1, (int) Math.round((double) bitCount / expectedInsertions * ln2));
        this.bits = new long[bitCount / 64];
    }

    public void add(long value) {
        int[] positions = positions(value);
        for (int position : positions) {
            bits[position >>> 6] |= 1L << (position & 63);
        }
    }

    public boolean mightContain(long value) {
        for (int position : positions(value)) {
            if ((bits[position >>> 6] & (1L << (position & 63))) == 0) {
                return false;
            }
        }
        return true;
    }

    public int bitCount() {
        return bitCount;
    }

    public int hashCount() {
        return hashCount;
    }

    private int[] positions(long value) {
        long h1 = mix(value);
        long h2 = mix(value + 0x9E3779B97F4A7C15L) | 1L;
        int[] positions = new int[hashCount];
        for (int i = 0; i < hashCount; i++) {
            long combined = h1 + i * h2;
            positions[i] = (int) Math.floorMod(combined, (long) bitCount);
        }
        return positions;
    }

    /** MurmurHash3 的 finalizer：把任意 long 打散成均匀分布。 */
    private static long mix(long value) {
        long z = value * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }
}
