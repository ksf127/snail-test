package com.company.service.shortlink.support;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/**
 * 主链路的计数器。
 *
 * <p>【解决什么问题】短链系统调优的每一个决策（布隆开不开、L1 多大、单飞是否生效）
 * 都必须有数据支撑。这个类为每一层各准备一个计数器：请求进来多少、布隆挡掉多少、
 * L1/L2 各命中多少、回源多少次、单飞降级多少次。
 * 去掉它，调优只能靠猜，也无法判断一次配置变更到底是变好还是变坏。
 *
 * <p>【为什么这样设计】全部用 {@link LongAdder} 而不是 AtomicLong：
 * <ul>
 *   <li>百万 QPS 下多个线程会频繁自增同一个计数器，LongAdder 分段累加，竞争远小于 CAS 单点；</li>
 *   <li>计数器只增不减、只用于观察，允许读取时存在极短的误差，不需要强一致；</li>
 *   <li>每个指标独立计数，{@link #snapshot()} 一次读出，保证响应里各字段来自同一次采样。</li>
 * </ul>
 * 为什么必须同时看"命中率"和"绝对次数"：命中率 99.9% 在 100 万 QPS 下
 * 仍然意味着 1000 QPS 打到 L2。
 *
 * <p>【怎么读这段代码】前半部分是 markXxx 自增方法，在 {@code ShortlinkService}
 * 的关键分支上各调用一次；后半部分是 getter 和 {@link #snapshot()}。
 * LongAdder 本身线程安全，本类无其他可变状态，因此整体线程安全。
 */
@Component
public class ShortlinkMetrics {

    /**
     * 创建短链的次数。
     */
    private final LongAdder created = new LongAdder();

    /**
     * 删除短链的次数。
     */
    private final LongAdder deleted = new LongAdder();

    /**
     * 解析请求总数（含非法短码、布隆拦截）。
     */
    private final LongAdder resolveRequests = new LongAdder();

    /**
     * 解析成功的次数。
     */
    private final LongAdder resolved = new LongAdder();

    /**
     * 解析失败的次数（非法/不存在/已过期）。
     */
    private final LongAdder notFound = new LongAdder();

    /**
     * 被布隆过滤器判定"一定不存在"而拦下的次数。
     * 这个数字越大，说明布隆挡掉的无效流量越多，越能体现它的价值。
     */
    private final LongAdder bloomRejects = new LongAdder();

    /**
     * 真正回源到存储的次数。缓存健康时这个值应该非常小。
     */
    private final LongAdder dbLoads = new LongAdder();

    /**
     * 单飞锁拿不到、降级直接查库的次数。持续大于 0 说明锁等待时间过短或锁服务异常。
     */
    private final LongAdder singleFlightFallbacks = new LongAdder();

    public void markCreated() {
        created.increment();
    }

    public void markDeleted() {
        deleted.increment();
    }

    public void markResolveRequested() {
        resolveRequests.increment();
    }

    public void markResolved() {
        resolved.increment();
    }

    public void markNotFound() {
        notFound.increment();
    }

    public void markBloomRejected() {
        bloomRejects.increment();
    }

    public void markDbLoaded() {
        dbLoads.increment();
    }

    public void markSingleFlightFallback() {
        singleFlightFallbacks.increment();
    }

    public long created() {
        return created.sum();
    }

    public long deleted() {
        return deleted.sum();
    }

    public long resolveRequests() {
        return resolveRequests.sum();
    }

    public long resolved() {
        return resolved.sum();
    }

    public long notFound() {
        return notFound.sum();
    }

    public long bloomRejects() {
        return bloomRejects.sum();
    }

    public long dbLoads() {
        return dbLoads.sum();
    }

    public long singleFlightFallbacks() {
        return singleFlightFallbacks.sum();
    }

    /**
     * 一次性拍一张所有计数的快照。
     *
     * <p>各字段是分别 sum() 的，不是原子快照，但足以用于展示与告警。
     *
     * @return 当前累计计数
     */
    public Snapshot snapshot() {
        return new Snapshot(created.sum(), deleted.sum(), resolveRequests.sum(), resolved.sum(),
                notFound.sum(), bloomRejects.sum(), dbLoads.sum(), singleFlightFallbacks.sum());
    }

    /**
     * 计数快照。
     *
     * @param created               创建次数
     * @param deleted               删除次数
     * @param resolveRequests       解析请求总数
     * @param resolved              解析成功次数
     * @param notFound              解析失败次数
     * @param bloomRejects          布隆拦截次数
     * @param dbLoads               回源存储次数
     * @param singleFlightFallbacks 单飞锁降级次数
     */
    public record Snapshot(long created,
                           long deleted,
                           long resolveRequests,
                           long resolved,
                           long notFound,
                           long bloomRejects,
                           long dbLoads,
                           long singleFlightFallbacks) {
    }
}
