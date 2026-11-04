package com.company.service.shortlink.infra;

/**
 * 布隆过滤器位图参数与哈希位置计算。
 *
 * <p>【解决什么问题】布隆过滤器的正确性完全取决于两件事：位图开多大（m）、每个 key 算几个位置（k）。
 * 两个实现（内存版、Redis 版）必须用完全相同的 m、k 和哈希算法，否则同一个 key
 * 在写入端和读取端会落在不同位置，出现"刚写入就查不到"的假阴性 —— 这是致命的。
 * 把这些公式和哈希收敛到一个包级私有类里，就是为了让两个实现不可能算出不同结果。
 *
 * <p>【为什么这样设计】参数由"预期元素个数 n"和"可接受误判率 p"反推：
 * <ul>
 *   <li>最优位数 {@code m = -n * ln(p) / (ln2)^2}，即每个元素约 1.44 * log2(1/p) bit；</li>
 *   <li>最优哈希次数 {@code k = (m/n) * ln2}；</li>
 *   <li>k 被夹在 [1,16]：太小误判率高，太大哈希开销浪费且收益递减；</li>
 *   <li>k 个位置用 Kirsch-Mitzenmacher 双哈希生成，只需算两次哈希，避免真的算 k 次。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】{@link #bitSize} 与 {@link #hashCount} 是一组公式，先看注释再代入；
 * {@link #positions} 是热点路径；{@link #hash64} + {@link #fmix64} 是哈希原语。
 * 全部是静态纯函数，无状态，线程安全。
 *
 * <p>【边界条件】
 * <ul>
 *   <li>{@code p} 必须落在 (0,1)：p=0 会让 ln(p) 变成 -Infinity；</li>
 *   <li>位数下限固定 64，保证位图至少有一个 long 字，避免空数组；</li>
 *   <li>位置用无符号取余 {@code Long.remainderUnsigned}，因为哈希值可能是负数，
 *       直接用 {@code %} 会得到负下标。</li>
 * </ul>
 */
final class BloomMath {

    private BloomMath() {
    }

    /**
     * 按预期元素个数与误判率计算位图位数 m。
     *
     * @param expectedInsertions 预期插入的元素个数 n，必须大于 0
     * @param falsePositiveRate  可接受误判率 p，必须落在 (0,1)
     * @return 位图位数，最少 64
     */
    static long bitSize(long expectedInsertions, double falsePositiveRate) {
        // 公式：m = -n * ln(p) / (ln2)^2
        double m = -expectedInsertions * Math.log(falsePositiveRate) / (Math.log(2) * Math.log(2));
        return Math.max(64, (long) Math.ceil(m));
    }

    /**
     * 按位图大小与元素个数计算哈希次数 k。
     *
     * <p>k 被夹在 [1,16]：保证至少算一次，同时避免 k 过大导致每次判断要访问太多位。
     *
     * @param bitSize            位图位数 m，必须大于 0
     * @param expectedInsertions 预期元素个数 n，必须大于 0
     * @return 哈希次数，范围 [1,16]
     */
    static int hashCount(long bitSize, long expectedInsertions) {
        // 公式：k = (m / n) * ln2
        double k = (double) bitSize / expectedInsertions * Math.log(2);
        return Math.max(1, Math.min(16, (int) Math.round(k)));
    }

    /**
     * Kirsch-Mitzenmacher 双哈希：用 h1 + i*h2 生成 k 个位置，避免真的算 k 次哈希。
     *
     * <p>理论前提是两个哈希函数相互独立；实践中 h2 由 h1 再混一次得到，
     * 并强制置为奇数（{@code | 1}），减少与 bitSize 产生公因子后位置退化的概率。
     *
     * <p>这是热路径：每个短码要算一次，返回的数组长度等于 hashCount。
     * 方法本身不共享状态，线程安全。
     *
     * @param key      待计算的短码
     * @param bitSize  位图位数，必须大于 0
     * @param hashCount 哈希次数，必须大于 0
     * @return 长度为 hashCount 的位下标数组，每个元素落在 [0, bitSize)
     */
    static long[] positions(String key, long bitSize, int hashCount) {
        long h1 = hash64(key);
        // h2 用同一个混合函数再打散一次；| 1 保证 h2 为奇数
        long h2 = fmix64(h1 + 0x9E3779B97F4A7C15L) | 1L;
        long[] positions = new long[hashCount];
        long base = h1;
        for (int i = 0; i < hashCount; i++) {
            // 无符号取余：哈希值可能是负数，有符号 % 会产生负下标
            positions[i] = Long.remainderUnsigned(base, bitSize);
            // 逐个累加 h2，等价于第 i 个哈希 h1 + i*h2，但省掉乘法
            base += h2;
        }
        return positions;
    }

    /**
     * FNV-1a 64 位哈希，最后再过一遍 fmix64 做雪崩。
     *
     * <p>选 FNV-1a 是因为它实现简单、无分支，且对短字符串（这里只有 7 位）足够均匀。
     * 注意它是基于 char 的（不是字节），但同一进程内读写一致即可，不涉及跨语言兼容。
     */
    private static long hash64(String key) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 0x100000001b3L;
        }
        return fmix64(hash);
    }

    /**
     * MurmurHash3 的 64 位 finalizer：让低位的变化扩散到高位。
     *
     * <p>这一步很关键：位下标是用无符号取余算的，主要看低位；
     * 如果哈希的低位分布不均，位图会被局部写满，实际误判率远高于理论值。
     */
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
