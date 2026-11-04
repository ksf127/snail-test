package com.company.service.archlab.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 极简布隆过滤器：位图 + 双重哈希，用来挡住"查不存在 id"的穿透流量。
 *
 * <p>【解决什么问题】缓存穿透指的是请求一个数据库里根本不存在的 id：
 * 缓存永远不命中，请求每次都穿过缓存打到数据库。正常业务里这可能是脏参数，
 * 被人恶意构造时就成了打库攻击——一个不存在的 id 就能让数据库承担全部 QPS。
 * 布隆过滤器放在缓存之前，用极小的内存提前判定"这个 id 一定不存在"，直接拒绝掉。
 *
 * <p>【为什么这样设计】它只用一个位数组加 k 个哈希，每个元素平均只占几个 bit，
 * 内存是普通集合的几十分之一。代价是多个元素共享同一批位：
 * 某个元素明明没被加入，它的 k 个位却可能被别的元素碰巧置 1，于是被误判成"可能存在"（假阳性）；
 * 反过来，只要有一个位是 0，它就一定没被加入过（无假阴性）。
 * 所以它只能回答"一定不存在 / 可能存在"，只能用来<b>提前拒绝</b>，不能用来确认存在，
 * 最终判断仍然要回到缓存/数据库。也正因为位是共享的，布隆过滤器<b>不能删除元素</b>——
 * 删一个会误伤其他元素。
 *
 * <p>【怎么读这段代码】先看构造方法：位数组长度 m 与哈希次数 k 由预期元素数 n 和误判率 p
 * 共同决定（公式在构造函数注释里）。然后看 {@link #positions(long)} 如何用双重哈希模拟出
 * k 个哈希位置；最后对照 {@link #add(long)} 与 {@link #mightContain(long)}——
 * 它们对同一批位置做置 1 / 查 0，一个写一个读，逻辑必须严格对称。
 *
 * <p>关键性质：<b>可能存在（有假阳性），一定不存在（无假阴性）</b>。
 * 所以它只能用来"提前拒绝"，不能用来"确认存在"——最终判断仍然要回到缓存/数据库。
 *
 * <p>生产里用 RedisBloom/Guava，但原理就是这几十行：
 * 位数组长度与哈希次数由预期元素数 n 和误判率 p 共同决定。
 */
public final class BloomFilter {

    private static final Logger log = LoggerFactory.getLogger(BloomFilter.class);

    /** 位图：一个 long 装 64 个位，避免用 boolean[] 浪费 8 倍以上内存。 */
    private final long[] bits;

    private final int bitCount;

    private final int hashCount;

    /**
     * @param expectedInsertions 预期要放入的元素个数；估小了误判率会明显上升
     * @param falsePositiveRate  可接受的误判率，例如 0.001 表示千分之一
     * @throws IllegalArgumentException 预期元素数不大于 0，或误判率不在 (0, 1) 区间时抛出
     */
    public BloomFilter(int expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions 必须大于 0");
        }
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1) {
            throw new IllegalArgumentException("falsePositiveRate 必须在 (0, 1) 之间");
        }
        double ln2 = Math.log(2);
        // 位数组长度按 m = -n·ln(p)/(ln2)² 计算：
        // p 越小需要越多位，n 越大也越多位，这是"内存换误判率"的直接体现。
        int rawBits = (int) Math.ceil(-expectedInsertions * Math.log(falsePositiveRate) / (ln2 * ln2));
        // 向上取整到 64 的倍数，保证 bits 数组长度是整数；最少 64 位，避免 m 太小导致哈希全撞在一起。
        this.bitCount = Math.max(64, (rawBits + 63) / 64 * 64);
        // k = (m/n)·ln2 是最优哈希次数：再少误判率高，再多只是白白增加计算量。
        this.hashCount = Math.max(1, (int) Math.round((double) bitCount / expectedInsertions * ln2));
        this.bits = new long[bitCount / 64];
        log.debug("【布隆过滤器】初始化完成，预期元素={}，误判率={}，位图长度={}，哈希次数={}",
                expectedInsertions, falsePositiveRate, bitCount, hashCount);
    }

    /**
     * 把一个确定存在的值写进位图。
     *
     * <p>只在商品入库或缓存预热时调用；读路径不会调用它。
     *
     * @param value 业务主键，这里是商品 id
     */
    public void add(long value) {
        int[] positions = positions(value);
        for (int position : positions) {
            // position >>> 6 定位到 long 下标，position & 63 定位到该 long 里的第几位。
            // 同一批位置必须和 mightContain 完全一致，否则写进去的位查不到。
            bits[position >>> 6] |= 1L << (position & 63);
        }
    }

    /**
     * 判断一个值"是否可能存在"。
     *
     * @param value 业务主键
     * @return {@code false} 表示<b>一定不存在</b>，可以直接拒绝，连缓存和数据库都不用查；
     *         {@code true} 只表示"可能存在"（有假阳性），仍需继续查缓存/数据库确认
     */
    public boolean mightContain(long value) {
        for (int position : positions(value)) {
            // 只要有一个位是 0，就说明这个值从没被 add 过：无假阴性，可以安全拒绝。
            if ((bits[position >>> 6] & (1L << (position & 63))) == 0) {
                return false;
            }
        }
        // 所有位都是 1：可能确实是它，也可能是别的元素把它们填满了，只能放行。
        return true;
    }

    public int bitCount() {
        return bitCount;
    }

    public int hashCount() {
        return hashCount;
    }

    /**
     * 用双重哈希（h1 + i·h2）模拟出 k 个哈希位置。
     *
     * <p>真正的布隆过滤器需要 k 个独立哈希函数，每次都算一遍代价高；
     * 双重哈希只需算两个基础哈希，再用线性组合派生出 k 个，效果等价且更便宜。
     */
    private int[] positions(long value) {
        long h1 = mix(value);
        // h2 强制为奇数：与 bitCount 有公因数时，线性组合仍能较均匀地铺满整个位图。
        long h2 = mix(value + 0x9E3779B97F4A7C15L) | 1L;
        int[] positions = new int[hashCount];
        for (int i = 0; i < hashCount; i++) {
            // 对 bitCount 取模把位置收敛到 [0, bitCount)；
            // 用 floorMod 而不是 %，避免 combined 溢出成负数时得到负下标。
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
