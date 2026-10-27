package com.company.service.shortlink.infra;

/**
 * 布隆过滤器位图参数与哈希位置计算。
 *
 * <p>放在单独一个类里，是为了让内存版与 Redis 版读懂同一套参数：
 * 位图大小 m 与哈希次数 k 一旦不一致，两个实现的判断结果就对不上。
 */
final class BloomMath {

    private BloomMath() {
    }

    static long bitSize(long expectedInsertions, double falsePositiveRate) {
        double m = -expectedInsertions * Math.log(falsePositiveRate) / (Math.log(2) * Math.log(2));
        return Math.max(64, (long) Math.ceil(m));
    }

    static int hashCount(long bitSize, long expectedInsertions) {
        double k = (double) bitSize / expectedInsertions * Math.log(2);
        return Math.max(1, Math.min(16, (int) Math.round(k)));
    }

    /**
     * Kirsch-Mitzenmacher 双哈希：用 h1 + i*h2 生成 k 个位置，避免真的算 k 次哈希。
     */
    static long[] positions(String key, long bitSize, int hashCount) {
        long h1 = hash64(key);
        long h2 = fmix64(h1 + 0x9E3779B97F4A7C15L) | 1L;
        long[] positions = new long[hashCount];
        long base = h1;
        for (int i = 0; i < hashCount; i++) {
            positions[i] = Long.remainderUnsigned(base, bitSize);
            base += h2;
        }
        return positions;
    }

    private static long hash64(String key) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001b3L;
        }
        return fmix64(hash);
    }

    private static long fmix64(long value) {
        long x = value;
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x;
    }
}
