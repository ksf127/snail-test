package com.company.service.shortlink.capacity;

import java.util.ArrayList;
import java.util.List;

/**
 * 容量模型：把"目标 QPS"翻译成"要几台机器、Redis 和数据库各扛多少"。
 *
 * <p>【解决什么问题】短链系统的瓶颈不在功能，而在"一次请求经过几层、每层命中多少"。
 * 这个类把 100 万 QPS 自顶向下拆成 L1 / L2 / 存储三段的真实流量，
 * 并用利用率（当前压力 / 该层容量）判断是否可行。去掉它，
 * 压测报告只能告诉你"单机跑多少"，没人能说清"L1 命中率掉 1% 会怎样"。
 *
 * <p>【为什么这样设计】必须同时给出节点数、流量拆分、利用率和告警，而不是只算节点数：
 * <ul>
 *   <li>100 万 QPS 的系统真正的风险在连锁放大 —— L1 命中率掉 1%，Redis 就多 1 万 QPS；</li>
 *   <li>容量不能算到 100%，这里留 30% 余量（{@link #SAFETY_HEADROOM}）给流量尖峰、
 *       节点故障和重试；</li>
 *   <li>命中率的分母必须写清楚：{@code l2HitRatioOfL1Miss} 的分母是"L1 未命中"，
 *       不是全部请求，混用会严重高估缓存效果。</li>
 * </ul>
 * 流量拆分公式（自顶向下）：
 * <pre>
 *   L2 QPS  = targetQps * (1 - l1HitRatio)
 *   DB QPS  = L2 QPS * (1 - l2HitRatioOfL1Miss)
 * </pre>
 *
 * <p>【怎么读这段代码】先看两个 record 的字段（输入的 {@link Workload}、
 * 输出的 {@link Plan}），再读 {@link #plan(Workload)}：计算节点数 → 拆分流量 →
 * 算利用率 → 生成告警。{@code validate} 只在入口校验一次比例的取值范围。
 */
public final class CapacityModel {

    /**
     * 安全水位：容量用到 70% 就要扩容，剩下的 30% 留给流量尖峰、节点故障和第 N+1 次重试。
     */
    public static final double SAFETY_HEADROOM = 0.7;

    private CapacityModel() {
    }

    /**
     * 容量模型的输入。
     *
     * @param targetQps           目标总 QPS，必须大于 0
     * @param l1HitRatio          L1 本地缓存命中率，范围 [0,1]；乘的是全部请求
     * @param l2HitRatioOfL1Miss  L2 命中率，范围 [0,1]；<b>分母是 L1 未命中</b>，不是全部请求
     * @param singleNodeQps       单机可承载 QPS，必须大于 0
     * @param redisQpsCapacity    Redis 集群的容量上限 QPS，必须大于 0
     * @param dbQpsCapacity       存储层（含只读副本）的容量上限 QPS，必须大于 0
     */
    public record Workload(long targetQps,
                           double l1HitRatio,
                           double l2HitRatioOfL1Miss,
                           long singleNodeQps,
                           long redisQpsCapacity,
                           long dbQpsCapacity) {
    }

    /**
     * 容量模型的输出。
     *
     * @param targetQps        回显的目标 QPS
     * @param nodes            需要的节点数（向上取整）
     * @param qpsPerNode       平均每节点 QPS
     * @param l1Qps            被 L1 接住的 QPS
     * @param l2Qps            穿透到 L2 的 QPS
     * @param dbQps            穿透到存储的 QPS
     * @param redisUtilization Redis 利用率，&gt;1 表示超容
     * @param dbUtilization    存储利用率，&gt;1 表示超容
     * @param feasible         两层是否都在安全水位以内
     * @param warnings         人可读的告警，按严重程度排列
     */
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

    /**
     * 计算容量方案。
     *
     * <p>纯函数：同样的入参永远得到同样的结果，没有共享状态，因此线程安全。
     *
     * @param workload 目标 QPS 与各层命中率、容量
     * @return 节点数、流量拆分、利用率与告警
     * @throws IllegalArgumentException 入参为 null 或任一比例/容量越界时
     */
    public static Plan plan(Workload workload) {
        validate(workload);

        // 节点数向上取整：少一台就扛不住目标 QPS
        int nodes = (int) Math.ceil(workload.targetQps() / (double) workload.singleNodeQps());
        long qpsPerNode = (long) Math.ceil(workload.targetQps() / (double) nodes);
        // 先算 L2，再用减法反推 L1：避免两段各自四舍五入后加起来不等于总 QPS
        long l2Qps = Math.round(workload.targetQps() * (1 - workload.l1HitRatio()));
        long l1Qps = workload.targetQps() - l2Qps;
        // 注意这里的乘数是"L1 未命中里的占比"，不是总请求
        long dbQps = Math.round(l2Qps * (1 - workload.l2HitRatioOfL1Miss()));
        double redisUtilization = l2Qps / (double) workload.redisQpsCapacity();
        double dbUtilization = dbQps / (double) workload.dbQpsCapacity();
        // 两层都必须在 70% 以内才算可行
        boolean feasible = redisUtilization <= SAFETY_HEADROOM && dbUtilization <= SAFETY_HEADROOM;

        List<String> warnings = new ArrayList<>();
        // 超容（>100%）比"超过水位但还能跑"更严重，所以用 if/else 只报最高级别那条
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
            // 用 1% 做单位，是因为在百万 QPS 量级下每 1% 就是 1 万 QPS 的放大
            warnings.add("L1 命中率 " + percent(workload.l1HitRatio())
                    + " 偏低：每降 1% 就多 " + Math.round(workload.targetQps() * 0.01) + " QPS 打到 Redis");
        }

        // List.copyOf 生成不可变列表，防止调用方改掉告警内容
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

    /**
     * 入口校验：把非法输入挡在计算之前，避免出现负数节点或 NaN。
     *
     * @throws IllegalArgumentException 任一参数非法时
     */
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
        // 容量为 0 会让利用率变成 Infinity/NaN，必须在入口挡掉
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

    /**
     * 利用率保留 4 位小数，避免响应里出现 0.6666666666666666 这种噪声。
     */
    private static double round(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private static String percent(double ratio) {
        return String.format("%.2f%%", ratio * 100);
    }
}
