package com.company.service.shortlink.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 点击存储：HINCRBY 累加，天然支持多实例汇总。
 *
 * <p>【解决什么问题】内存版点击计数在重启后清零、多实例之间也无法合并。
 * 用一个 Redis Hash 保存"短码 → 点击量"，所有实例的增量都累加到同一份数据上，
 * 既不会丢，也不需要对每个短码建一个 key（Hash 比成千上万个 String key 更省内存）。
 *
 * <p>【为什么这样设计】只提供"原子自增"和"读取"，不做批量刷新：
 * 跳转主路径把增量攒在内存（见 {@code ClickRecorder}），每秒刷新一次，
 * 这样 Redis 只承受每秒一次批量的写入，而不是 100 万 QPS 的写。
 * 选 Hash 而不是 String，是因为一次 HINCRBY 只影响一个 field，不会重写整个对象。
 *
 * <p>【怎么读这段代码】{@link #increment} 对应 HINCRBY，{@link #clicks} 对应 HGET。
 * StringRedisTemplate 线程安全，本类没有额外可变状态，因此整体线程安全。
 */
public class RedisClickStore implements ClickStore {

    private static final Logger log = LoggerFactory.getLogger(RedisClickStore.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 点击量 Hash 的 key（配置项 shortlink.click.hash-key）。
     */
    private final String hashKey;

    public RedisClickStore(StringRedisTemplate redisTemplate, String hashKey) {
        this.redisTemplate = redisTemplate;
        this.hashKey = hashKey;
        log.info("Redis 点击存储初始化: hashKey={}", hashKey);
    }

    /**
     * 在 Hash 里对指定 field 原子累加。
     *
     * @param code  短码，作为 Hash 的 field
     * @param delta 本次增量，通常大于 0
     */
    @Override
    public void increment(String code, long delta) {
        // HINCRBY 在服务端原子完成，多实例并发也不会丢增量
        redisTemplate.opsForHash().increment(hashKey, code, delta);
    }

    /**
     * 读取某个短码的已落盘点击量。
     *
     * @param code 短码
     * @return 点击量；Hash 中不存在该 field 时返回 0
     */
    @Override
    public long clicks(String code) {
        Object value = redisTemplate.opsForHash().get(hashKey, code);
        if (value == null) {
            return 0;
        }
        return Long.parseLong(value.toString());
    }
}
