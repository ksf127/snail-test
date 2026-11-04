package com.company.service.shortlink.web.dto;

import com.company.service.shortlink.service.ShortlinkService;

/**
 * 运行期计数快照 + 由计数推导出的命中率。
 *
 * <p>【解决什么问题】它把内部计数器翻译成"能直接判断系统健康度"的一组数字：
 * 各层命中多少、布隆挡掉多少、真正回源多少次、单飞降级多少次。
 * 没有它，调参只能靠猜，也无法回答"这次把 L1 容量调大到底有没有用"。
 *
 * <p>【为什么这样设计】命中率一定要和绝对次数一起看：
 * 命中率 99.9% 在 100 万 QPS 下仍然意味着 1000 QPS 打到 L2。
 * 三个命中率的分母都是"缓存查找总次数"（L1+L2+MISS），
 * 且 {@code cacheHitRatio = l1HitRatio + l2HitRatio}，因此三者可以直接相加对照。
 *
 * <p>【怎么读这段代码】先看 {@link #from}：它先算分母，再分别求 L1/L2 占比。
 * {@code ratio} 在分母为 0（服务刚启动）时返回 0，避免出现 NaN；
 * {@code round} 统一保留 4 位小数。record 不可变，天然线程安全。
 *
 * @param links                 存储中的短链总数
 * @param cacheL1Hits           L1 命中次数
 * @param cacheL2Hits           L2 命中次数
 * @param cacheMisses           两层都未命中、需要回源的次数
 * @param l1HitRatio            L1 命中率（分母为缓存查找总数）
 * @param l2HitRatio            L2 命中率（分母同上）
 * @param cacheHitRatio         综合命中率 = L1 + L2
 * @param cacheL1EstimatedSize  L1 当前估算条目数
 * @param created               创建次数
 * @param deleted               删除次数
 * @param resolveRequests       解析请求总数
 * @param resolved              解析成功次数
 * @param notFound              解析失败次数（非法/不存在/过期）
 * @param bloomRejects          被布隆过滤器判定"一定不存在"而拦下的次数
 * @param dbLoads               真正回源存储的次数
 * @param singleFlightFallbacks 单飞锁等待超时后降级查库的次数
 * @param clicksRecorded        累计上报的点击次数
 * @param clicksFlushed         累计已落盘的点击次数
 * @param clicksPending         尚未落盘的点击次数
 * @param remoteCache           当前 L2 实现（memory/redis）
 * @param lockProvider          当前分布式锁实现
 */
public record ShortlinkStatsView(long links,
                                 long cacheL1Hits,
                                 long cacheL2Hits,
                                 long cacheMisses,
                                 double l1HitRatio,
                                 double l2HitRatio,
                                 double cacheHitRatio,
                                 long cacheL1EstimatedSize,
                                 long created,
                                 long deleted,
                                 long resolveRequests,
                                 long resolved,
                                 long notFound,
                                 long bloomRejects,
                                 long dbLoads,
                                 long singleFlightFallbacks,
                                 long clicksRecorded,
                                 long clicksFlushed,
                                 long clicksPending,
                                 String remoteCache,
                                 String lockProvider) {

    /**
     * 从 Service 的内部统计对象转换为对外视图。
     *
     * <p>纯函数，无状态，线程安全。
     *
     * @param stats 内部计数快照，不能为 null
     * @return 含派生命中率的对外视图
     */
    public static ShortlinkStatsView from(ShortlinkService.ShortlinkStats stats) {
        // 分母 = 所有"走到缓存层"的请求数；布隆拦截的请求没进缓存，因此不在这里的分母里
        long lookups = stats.cacheL1Hits() + stats.cacheL2Hits() + stats.cacheMisses();
        double l1Ratio = ratio(stats.cacheL1Hits(), lookups);
        double l2Ratio = ratio(stats.cacheL2Hits(), lookups);
        return new ShortlinkStatsView(
                stats.links(),
                stats.cacheL1Hits(),
                stats.cacheL2Hits(),
                stats.cacheMisses(),
                l1Ratio,
                l2Ratio,
                // 综合命中率直接相加：两个比率的分母相同
                l1Ratio + l2Ratio,
                stats.cacheL1EstimatedSize(),
                stats.created(),
                stats.deleted(),
                stats.resolveRequests(),
                stats.resolved(),
                stats.notFound(),
                stats.bloomRejects(),
                stats.dbLoads(),
                stats.singleFlightFallbacks(),
                stats.clicksRecorded(),
                stats.clicksFlushed(),
                stats.clicksPending(),
                stats.remoteCache(),
                stats.lockProvider());
    }

    /**
     * 安全求比例：分母为 0 时返回 0，避免出现 NaN/Infinity。
     */
    private static double ratio(long part, long total) {
        return total == 0 ? 0 : round(part / (double) total);
    }

    /**
     * 保留 4 位小数，避免响应里出现 0.9523809523809523 这类噪声。
     */
    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }
}
