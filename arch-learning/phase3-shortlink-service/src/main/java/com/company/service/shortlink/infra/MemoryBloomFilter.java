package com.company.service.shortlink.infra;

import com.company.service.shortlink.domain.BloomFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 内存布隆过滤器：位图 + 原子位操作。
 *
 * <p>【解决什么问题】它是跳转链路的<b>第一道闸门</b>：用一块连续的 long 数组当作位图，
 * 零 IO、零网络，单次判断只有 k 次数组访问。100 万 QPS 下随机扫描的短码在这里就被挡掉，
 * 不会污染 L2 缓存、也不会打到数据库。去掉它，缓存会被不存在 key 挤占，数据库会被扫穿。
 *
 * <p>【为什么这样设计】
 * <ul>
 *   <li>为什么放缓存前面：位运算比一次 Redis 往返快一到两个数量级，能挡在最外层；</li>
 *   <li>为什么用 {@code AtomicLongArray} 而不是 {@code BitSet}：BitSet 不是线程安全的，
 *       而这里会被大量请求线程并发读、创建线程并发写；</li>
 *   <li>为什么 set 用 CAS 死循环：位图是多个线程共享的可变状态，
 *       必须保证"读-改-写"原子，否则并发写会互相覆盖对方的位。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】先看构造器：由预期条数和误判率算出位图长度与哈希次数（公式见 {@link BloomMath}），
 * 再换算成 long 数组长度 {@code (bitSize + 63) >>> 6}（右移 6 位即除以 64，向上取整）。
 * 然后看 {@link #mightContain}：任一位置为 0 就立刻返回 false，只有 k 位全为 1 才说"可能存在"。
 * 最后看 {@link #isSet}/{@link #set} 的位运算：{@code position >>> 6} 定位到 long，
 * {@code 1L << (position & 63)} 定位到 long 内的 bit。
 *
 * <p>【并发安全】读操作只依赖 AtomicLongArray 的可见性保证；写操作对单个 long 做 CAS。
 * 不同线程写同一个 long 的不同 bit 时，CAS 失败会重试并保留对方的位，因此不会丢写。
 * 整个类线程安全。
 *
 * <p>【容量参考】1 亿条、误判率 1e-4 时大约占 240MB，因此真实环境用 Redis 位图分摊到服务端；
 * 这里保留一份内存实现，是为了单测能在毫秒级验证"假阳性有上界、假阴性为零"。
 */
public class MemoryBloomFilter implements BloomFilter {

    private static final Logger log = LoggerFactory.getLogger(MemoryBloomFilter.class);

    /**
     * 位图总位数 m，由预期条数和误判率算出。
     */
    private final long bitSize;

    /**
     * 每个 key 的哈希次数 k。
     */
    private final int hashCount;

    /**
     * 预期插入的元素个数 n，只用于日志与调参参考，不参与运行时判断。
     */
    private final long expectedInsertions;

    /**
     * 目标误判率 p，只用于日志与调参参考。
     */
    private final double falsePositiveRate;

    /**
     * 位图本体：每个 long 承载 64 个 bit。
     */
    private final AtomicLongArray words;

    /**
     * 已登记的元素个数（近似值，重复 add 也会 +1）。
     */
    private final AtomicLong added = new AtomicLong();

    /**
     * @param expectedInsertions 预期插入的元素个数，必须大于 0
     * @param falsePositiveRate  可接受误判率，必须落在 (0,1)
     * @throws IllegalArgumentException 参数越界时
     */
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
        // (bitSize + 63) >>> 6 等价于 ceil(bitSize / 64)：向上取整保证最后不满一个 long 的位也有位置
        this.words = new AtomicLongArray((int) ((bitSize + 63) >>> 6));
        log.info("内存布隆过滤器初始化: 预期条数={}, 误判率={}, 位数={}, 哈希次数={}, 占用={}B",
                expectedInsertions, falsePositiveRate, bitSize, hashCount, memoryBytes());
    }

    /**
     * 判断短码是否可能存在。
     *
     * <p>只要有一个位置是 0 就确定没加过；k 个位置全为 1 才返回 true（可能是别人置的位）。
     *
     * @param key 短码
     * @return 可能存在返回 true，一定不存在返回 false
     */
    @Override
    public boolean mightContain(String key) {
        for (long position : BloomMath.positions(key, bitSize, hashCount)) {
            if (!isSet(position)) {
                // 短路返回：越早发现 0 位越省时间，这也是布隆适合挡在最前面的原因
                return false;
            }
        }
        return true;
    }

    /**
     * 把短码登记进位图。
     *
     * <p>只置 1、从不清 0，因此删除无法从布隆中移除。
     *
     * @param key 短码
     */
    @Override
    public void add(String key) {
        for (long position : BloomMath.positions(key, bitSize, hashCount)) {
            set(position);
        }
        added.incrementAndGet();
    }

    /**
     * 读取某一位。
     *
     * @param position 位下标，必须落在 [0, bitSize)
     * @return 该位为 1 时返回 true
     */
    private boolean isSet(long position) {
        // position >>> 6：第几个 long；position & 63：该 long 内的第几个 bit
        long mask = 1L << (position & 63);
        return (words.get((int) (position >>> 6)) & mask) != 0;
    }

    /**
     * 把某一位原子地置 1。
     *
     * <p>用 CAS 重试而不是 {@code getAndUpdate}，是为了在已经置位时直接返回，少一次无谓的写。
     *
     * @param position 位下标，必须落在 [0, bitSize)
     */
    private void set(long position) {
        long mask = 1L << (position & 63);
        int wordIndex = (int) (position >>> 6);
        while (true) {
            long current = words.get(wordIndex);
            if ((current & mask) != 0) {
                // 已经是 1，无需再写
                return;
            }
            // 只把目标位或上去，保留同一个 long 里其他线程写入的位
            if (words.compareAndSet(wordIndex, current, current | mask)) {
                return;
            }
        }
    }

    /**
     * @return 位图总位数 m
     */
    public long bitSize() {
        return bitSize;
    }

    /**
     * @return 每个 key 的哈希次数 k
     */
    public int hashCount() {
        return hashCount;
    }

    /**
     * @return 累计 add 调用次数（重复 add 也计数）
     */
    public long addedCount() {
        return added.get();
    }

    /**
     * @return 位图占用的堆内存字节数
     */
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
