package com.company.service.shortlink.infra;

import com.company.service.shortlink.domain.Base62;
import com.company.service.shortlink.domain.Range;
import com.company.service.shortlink.domain.SegmentSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 号段来源：用 INCRBY 一次申请一整段号码。
 *
 * <p>【解决什么问题】多实例部署时，进程内自增会让每个实例都从 1 开始发号，短码必然重复。
 * 把"当前发到哪了"放到 Redis 的同一个 key 上，用 INCRBY 原子推进，
 * 所有实例拿到的区间天然互不重叠 —— 这是短链发号能做到"多实例安全"的关键。
 *
 * <p>【为什么这样设计】为什么号段模式比雪花 ID 更适合短链：
 * <ul>
 *   <li>雪花依赖机器时钟 + 机器号，时钟回拨会产生重复或乱序，运维成本高；</li>
 *   <li>号段只依赖 Redis 的原子自增，不关心时间，天然单调唯一；</li>
 *   <li>一次往返拿 step 个号，发号吞吐不再受网络往返限制；</li>
 *   <li>号码本身是小的递增整数，转 Base62 后正好 7 位，符合短码长度设计。</li>
 * </ul>
 * 代价是 Redis 不可用时创建接口不可用（跳转不受影响，因为跳转不发号）。
 *
 * <p>【怎么读这段代码】{@code INCRBY key step} 返回的是"这一段的最后一个号"（end），
 * 起点要用 {@code end - step + 1} 倒推；半开上界是 {@code end + 1}。
 * 注意 Base62 的上限比较必须在"已经自增之后"做，超了就把异常抛给上层。
 * 无共享可变状态，线程安全（原子性由 Redis 保证）。
 */
public class RedisSegmentSource implements SegmentSource {

    private static final Logger log = LoggerFactory.getLogger(RedisSegmentSource.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 号段计数器的 key（配置项 shortlink.segment.key），所有实例共用。
     */
    private final String key;

    public RedisSegmentSource(StringRedisTemplate redisTemplate, String key) {
        this.redisTemplate = redisTemplate;
        this.key = key;
        log.info("Redis 号段来源初始化: key={}", key);
    }

    /**
     * 申请下一段号码。
     *
     * @param step 号段长度，必须大于 0
     * @return 半开区间 {@code [end - step + 1, end + 1)}，长度恰好为 step
     * @throws IllegalStateException Redis 返回 null（自增失败）或号段超出 Base62 上限时
     */
    @Override
    public Range nextRange(int step) {
        // INCRBY 是原子操作：并发调用会各自拿到不同的 end，因此区间不会重叠
        Long end = redisTemplate.opsForValue().increment(key, step);
        if (end == null) {
            throw new IllegalStateException("Redis 号段自增失败: " + key);
        }
        if (end > Base62.MAX_ID) {
            throw new IllegalStateException("号段已耗尽: " + end);
        }
        // 起点是 end - step + 1；上界取 end + 1 以保持半开区间
        log.info("申请 Redis 号段: [{}, {})", end - step + 1, end + 1);
        return new Range(end - step + 1, end + 1);
    }
}
