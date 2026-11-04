package com.company.service.shortlink.infra;

import com.company.service.shortlink.domain.BloomFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Redis 布隆过滤器：所有实例共享同一张位图，重启不丢。
 *
 * <p>【解决什么问题】内存布隆过滤器有两个硬伤：多实例各存一份（位图不一致）、
 * 进程重启后位图全丢（需要重新预热，期间大量请求被误判为不存在）。
 * 把位图放到 Redis 上，所有实例读写同一份数据，重启也不需要重建 ——
 * 这是布隆过滤器在生产环境的标准部署方式。
 *
 * <p>【为什么这样设计】
 * <ul>
 *   <li>用 {@code SETBIT/GETBIT} 而不是自己序列化整张位图：Redis 原生支持按位寻址，
 *       不需要把几百 MB 的位图搬到客户端；</li>
 *   <li>用 pipeline 把 k 次 GETBIT/SETBIT 合成一次往返：否则 k=13 就是 13 次 RTT，
 *       单次判断会比查一次 Redis 还慢；</li>
 *   <li>跑在 Redis 上意味着它会消耗 Redis QPS，所以在容量模型里被算进 L2 压力，
 *       而不是应用服务器压力 —— 这是选择内存版还是 Redis 版的核心权衡。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】参数计算完全复用 {@link BloomMath}，保证与内存版一致；
 * {@link #mightContain} 先算好 k 个位置，再在一个 pipeline 里全部 GETBIT，
 * 最后逐一检查结果。所有方法无共享可变状态，线程安全（依赖 StringRedisTemplate 的线程安全）。
 */
public class RedisBloomFilter implements BloomFilter {

    private static final Logger log = LoggerFactory.getLogger(RedisBloomFilter.class);

    /**
     * Redis 客户端，由 Spring 注入，本身线程安全。
     */
    private final StringRedisTemplate redisTemplate;

    /**
     * 位图在 Redis 中的 key（已转成 UTF-8 字节，供底层 connection API 使用）。
     */
    private final byte[] key;

    /**
     * 位图总位数 m。
     */
    private final long bitSize;

    /**
     * 每个 key 的哈希次数 k。
     */
    private final int hashCount;

    /**
     * @param redisTemplate       Redis 客户端
     * @param key                 位图 key
     * @param expectedInsertions  预期元素个数，必须大于 0
     * @param falsePositiveRate   可接受误判率，必须落在 (0,1)
     */
    public RedisBloomFilter(StringRedisTemplate redisTemplate, String key, long expectedInsertions, double falsePositiveRate) {
        this.redisTemplate = redisTemplate;
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.bitSize = BloomMath.bitSize(expectedInsertions, falsePositiveRate);
        this.hashCount = BloomMath.hashCount(bitSize, expectedInsertions);
        log.info("Redis 布隆过滤器初始化: key={}, 预期条数={}, 误判率={}, 位数={}, 哈希次数={}",
                key, expectedInsertions, falsePositiveRate, bitSize, hashCount);
    }

    /**
     * 判断短码是否可能存在。
     *
     * <p>k 次 GETBIT 在一个 pipeline 里发出，只消耗一次网络往返。
     *
     * @param code 短码
     * @return 可能存在返回 true，一定不存在返回 false
     */
    @Override
    public boolean mightContain(String code) {
        long[] positions = BloomMath.positions(code, bitSize, hashCount);
        // executePipelined 会把回调里发出的所有命令打包，一次性发送并收集结果
        List<Object> bits = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (long position : positions) {
                connection.getBit(key, position);
            }
            // 回调的返回值不是结果；结果由 pipeline 收集每个命令的返回
            return null;
        });
        for (Object bit : bits) {
            // Redis GETBIT 返回 0/1，Spring 可能映射成 Boolean 或 byte[]，这里只认 TRUE
            if (!Boolean.TRUE.equals(bit)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把短码登记进位图。
     *
     * @param code 短码
     */
    @Override
    public void add(String code) {
        long[] positions = BloomMath.positions(code, bitSize, hashCount);
        // 同样用 pipeline：k 次 SETBIT 合成一次往返
        redisTemplate.executePipelined((RedisCallback<Object>) (RedisConnection connection) -> {
            for (long position : positions) {
                connection.setBit(key, position, true);
            }
            return null;
        });
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
}
