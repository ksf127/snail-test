package com.company.service.shortlink.infra;

import com.company.service.shortlink.domain.Base62;
import com.company.service.shortlink.domain.Range;
import com.company.service.shortlink.domain.SegmentSource;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 号段：INCRBY 返回的是"这一段最后一个号"，倒推出起点。
 *
 * <p>一次往返贡献 step 个号，多实例共享同一个 key，因此不会重复。
 * 代价是 Redis 不可用时创建接口不可用（跳转不受影响，因为跳转不发号）。
 */
public class RedisSegmentSource implements SegmentSource {

    private final StringRedisTemplate redisTemplate;

    private final String key;

    public RedisSegmentSource(StringRedisTemplate redisTemplate, String key) {
        this.redisTemplate = redisTemplate;
        this.key = key;
    }

    @Override
    public Range nextRange(int step) {
        Long end = redisTemplate.opsForValue().increment(key, step);
        if (end == null) {
            throw new IllegalStateException("Redis 号段自增失败: " + key);
        }
        if (end > Base62.MAX_ID) {
            throw new IllegalStateException("号段已耗尽: " + end);
        }
        return new Range(end - step + 1, end + 1);
    }
}
