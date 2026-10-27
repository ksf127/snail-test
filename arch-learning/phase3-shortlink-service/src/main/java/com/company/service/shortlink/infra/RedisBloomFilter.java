package com.company.service.shortlink.infra;

import com.company.service.shortlink.domain.BloomFilter;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Redis 布隆过滤器：所有实例共享同一张位图，重启不丢。
 *
 * <p>用 pipeline 把 k 次 GETBIT/SETBIT 合成一次往返；这决定了它比内存版慢一个数量级，
 * 所以在容量模型里它被算进 Redis QPS，而不是应用 QPS。
 */
public class RedisBloomFilter implements BloomFilter {

    private final StringRedisTemplate redisTemplate;

    private final byte[] key;

    private final long bitSize;

    private final int hashCount;

    public RedisBloomFilter(StringRedisTemplate redisTemplate, String key, long expectedInsertions, double falsePositiveRate) {
        this.redisTemplate = redisTemplate;
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.bitSize = BloomMath.bitSize(expectedInsertions, falsePositiveRate);
        this.hashCount = BloomMath.hashCount(bitSize, expectedInsertions);
    }

    @Override
    public boolean mightContain(String code) {
        long[] positions = BloomMath.positions(code, bitSize, hashCount);
        List<Object> bits = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (long position : positions) {
                connection.getBit(key, position);
            }
            return null;
        });
        for (Object bit : bits) {
            if (!Boolean.TRUE.equals(bit)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void add(String code) {
        long[] positions = BloomMath.positions(code, bitSize, hashCount);
        redisTemplate.executePipelined((RedisCallback<Object>) (RedisConnection connection) -> {
            for (long position : positions) {
                connection.setBit(key, position, true);
            }
            return null;
        });
    }

    public long bitSize() {
        return bitSize;
    }

    public int hashCount() {
        return hashCount;
    }
}
