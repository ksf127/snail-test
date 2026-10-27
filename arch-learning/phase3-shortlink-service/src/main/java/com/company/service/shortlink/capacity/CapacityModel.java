package com.company.service.shortlink.capacity;

import java.util.ArrayList;
import java.util.List;

/**
 * 容量模型：把"目标 QPS"翻译成"要几台机器、Redis 和数据库各扛多少"。
 *
 * <p>为什么必须有这一步：单机压测只能证明"一台能跑多少"，
 * 而 100 万 QPS 的系统真正的风险在连锁放大 —— L1 命中率掉 1%，Redis 就多 1 万 QPS。
 * 所以模型要同时给出流量拆分、利用率与告警，而不是只算一个节点数。
 *
 * <p>流量拆分公式（自顶向下）：
 * <pre>
 *   L2 QPS  = targetQps * (1 - l1HitRatio)
 *   DB QPS  = L2 QPS * (1 - l2HitRatioOfL1Miss)
 * </pre>
 * 注意 {@code l2HitRatioOfL1Miss} 的分母是"L1 未命中"，不是全部请求 —— 混用会高估缓存效果。
 */
public final class CapacityModel {

    /**
     * 安全水位：容量用到 70% 就要扩容，剩下的 30% 留给流量尖峰、节点故障和第 N+1 次重试。
     */
    public static final double SAFETY_HEADROOM = 0.7;

    private CapacityModel() {
    }

    public record Workload(long targetQps,
                           double l1HitRatio,
                           double l2HitRatioOfL1Miss,
                           long singleNodeQps,
                           long redisQpsCapacity,
                           long dbQpsCapacity) {
    }

    public record Plan(long targetQps,
                       int nodes,
                       long qpsPerNode,
                       long l1Qps,
                       long l2Qps,
                       long dbQps,
                       double redisUtilization,
                       double dbUtilization,
                       boolean feasible,
                       List<String> warnings) {
    }

    public static Plan plan(Workload workload) {
        validate(workload);

        int nodes = (int) Math.ceil(workload.targetQps() / (double) workload.singleNodeQps());
        long qpsPerNode = (long) Math.ceil(workload.targetQps() / (double) nodes);
        long l2Qps = Math.round(workload.targetQps() * (1 - workload.l1HitRatio()));
        long l1Qps = workload.targetQps() - l2Qps;
        long dbQps = Math.round(l2Qps * (1 - workload.l2HitRatioOfL1Miss()));
        double redisUtilization = l2Qps / (double) workload.redisQpsCapacity();
        double dbUtilization = dbQps / (double) workload.dbQpsCapacity();
        boolean feasible = redisUtilization <= SAFETY_HEADROOM && dbUtilization <= SAFETY_HEADROOM;

        List<String> warnings = new ArrayList<>();
        if (redisUtilization > 1) {
            warnings.add("L2(Redis) 需要 " + l2Qps + " QPS，超过容量 " + workload.redisQpsCapacity()
                    + "，必须分片或提升 L1 命中率");
        } else if (redisUtilization > SAFETY_HEADROOM) {
            warnings.add("L2(Redis) 利用率 " + percent(redisUtilization) + "，已超过 70% 安全水位");
        }
        if (dbUtilization > 1) {
            warnings.add("存储需要 " + dbQps + " QPS，超过容量 " + workload.dbQpsCapacity()
                    + "，需要加只读副本或扩负缓存 TTL");
        } else if (dbUtilization > SAFETY_HEADROOM) {
            warnings.add("存储利用率 " + percent(dbUtilization) + "，已超过 70% 安全水位");
        }
        if (workload.l1HitRatio() < 0.9) {
            warnings.add("L1 命中率 " + percent(workload.l1HitRatio())
                    + " 偏低：每降 1% 就多 " + Math.round(workload.targetQps() * 0.01) + " QPS 打到 Redis");
        }

        return new Plan(workload.targetQps(),
                nodes,
                qpsPerNode,
                l1Qps,
                l2Qps,
                dbQps,
                round(redisUtilization),
                round(dbUtilization),
                feasible,
                List.copyOf(warnings));
    }

    private static void validate(Workload workload) {
        if (workload == null) {
            throw new IllegalArgumentException("workload 不能为空");
        }
        if (workload.targetQps() <= 0) {
            throw new IllegalArgumentException("targetQps 必须大于 0: " + workload.targetQps());
        }
        if (workload.singleNodeQps() <= 0) {
            throw new IllegalArgumentException("singleNodeQps 必须大于 0: " + workload.singleNodeQps());
        }
        if (workload.redisQpsCapacity() <= 0 || workload.dbQpsCapacity() <= 0) {
            throw new IllegalArgumentException("redis/db 容量必须大于 0");
        }
        checkRatio("l1HitRatio", workload.l1HitRatio());
        checkRatio("l2HitRatioOfL1Miss", workload.l2HitRatioOfL1Miss());
    }

    private static void checkRatio(String name, double value) {
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " 必须落在 [0,1]: " + value);
        }
    }

    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private static String percent(double ratio) {
        return String.format("%.2f%%", ratio * 100);
    }
}
