package com.company.service.shortlink.web.dto;

import com.company.service.shortlink.service.ShortlinkService;

/**
 * 运行期计数快照 + 由计数推导出的命中率。
 *
 * <p>命中率一定要和绝对次数一起看：命中率 99.9% 在 100 万 QPS 下仍然意味着 1000 QPS 打到 L2。
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

    public static ShortlinkStatsView from(ShortlinkService.ShortlinkStats stats) {
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

    private static double ratio(long part, long total) {
        return total == 0 ? 0 : round(part / (double) total);
    }

    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }
}
