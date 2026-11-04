package com.company.service.archlab.scenario.distributedtx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seata Saga 的最小可运行复刻：<b>正向顺序执行 + 失败时反向补偿</b>。
 *
 * <p>【解决什么问题】跨服务的长事务不能靠数据库回滚。Saga 把业务拆成一串可单独提交的步骤，
 * 每一步成功后立刻落库，某一步失败时对已经执行的步骤逐个调用"反向动作"把影响抵消掉。
 * 于是不需要全局锁，也不会出现"一个分布式事务卡住三张表"。
 *
 * <p>【为什么这样设计】Saga 和 AT 的取舍正好相反：
 * <ul>
 *   <li>AT 靠全局锁把中间态藏起来；Saga 没有锁，也没有隔离性，
 *       中间态对任何读方都可见（例如"订单已创建、库存还没扣"）。</li>
 *   <li>AT 回滚依据 undo log 的镜像；Saga 回滚依据<b>每个正向步骤配一个补偿动作</b>，
 *       所以补偿必须由业务自己写，而且必须幂等 —— 补偿请求可能重复到达。</li>
 *   <li>补偿也可能失败（下游宕机、超时），因此必须有重试上限，
 *       超过上限就记为"补偿失败"交给人工/对账兜底，绝不能静默丢弃。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】{@link #run} 正序执行，每成功一步就把步骤名记进 {@code executed}；
 * 一旦抛错进入 {@link #compensate}，对 {@code executed} <b>倒序</b>逐个执行补偿，
 * 每个补偿最多试 {@code maxCompensationAttempts} 次。
 * 日志里能看到完整的 "正向第几步 → 补偿哪个步骤 → 第几次重试" 轨迹。
 *
 * <p>真实 Seata 里 Saga 由状态机引擎（{@code seata-saga-engine}）驱动，
 * 每个 {@code ServiceTask} 配一个 {@code CompensateState}；这里用
 * {@link SagaStep} 把"正向动作 + 补偿动作"绑在一起，把编排逻辑压缩成一次循环。
 */
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    /** 一个可补偿步骤：正向动作成功后才可能在回滚时执行 compensation。 */
    public record SagaStep(String name, Runnable action, Runnable compensation) {
    }

    public record SagaRun(boolean completed,
                          List<String> executed,
                          List<String> compensated,
                          List<String> failedCompensations,
                          int compensationRetries,
                          long elapsedMicros,
                          String failure) {
    }

    /** 补偿最多尝试的次数（含首次），超过即记为补偿失败。 */
    private final int maxCompensationAttempts;

    public SagaOrchestrator() {
        this(3);
    }

    /**
     * @param maxCompensationAttempts 单个步骤补偿的最大尝试次数（含首次），必须大于 0
     * @throws IllegalArgumentException 次数小于 1
     */
    public SagaOrchestrator(int maxCompensationAttempts) {
        if (maxCompensationAttempts < 1) {
            throw new IllegalArgumentException("maxCompensationAttempts 必须大于 0");
        }
        this.maxCompensationAttempts = maxCompensationAttempts;
    }

    /**
     * 顺序执行所有步骤；在 {@code failAt} 处抛错，然后对已执行步骤反向补偿。
     *
     * <p>非幂等：每次调用都会重新执行全部正向动作，并再次记录一条新的执行轨迹。
     *
     * @param sagaId 事务标识，只用于日志定位
     * @param steps  步骤列表，顺序即执行顺序
     * @param failAt 第几个步骤失败（0 基），-1 表示全部成功
     * @return 执行/补偿轨迹；补偿失败会体现在 {@code failedCompensations}
     * @throws IllegalArgumentException sagaId 为空、steps 为空或 failAt 越界
     */
    public SagaRun run(String sagaId, List<SagaStep> steps, int failAt) {
        if (sagaId == null || sagaId.isBlank()) {
            throw new IllegalArgumentException("sagaId 不能为空");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("steps 不能为空");
        }
        if (failAt < -1 || failAt >= steps.size()) {
            throw new IllegalArgumentException("failAt 必须在 [-1, " + (steps.size() - 1) + "] 之间");
        }
        long start = System.nanoTime();
        List<String> executed = new ArrayList<>();
        log.info("【Saga】开始: sagaId={}, 共 {} 步, failAt={}", sagaId, steps.size(), failAt);
        try {
            for (int i = 0; i < steps.size(); i++) {
                if (i == failAt) {
                    throw new IllegalStateException("模拟第 " + (i + 1) + " 个步骤失败");
                }
                SagaStep step = steps.get(i);
                // 正向步骤一旦执行就立刻生效：Saga 没有"未提交"状态，靠补偿而不是回滚
                step.action().run();
                executed.add(step.name());
                log.info("【Saga】正向步骤成功: sagaId={}, step={}, 进度={}/{}",
                        sagaId, step.name(), executed.size(), steps.size());
            }
            log.info("【Saga】全部成功: sagaId={}, 执行 {} 步", sagaId, executed.size());
            return new SagaRun(true, List.copyOf(executed), List.of(), List.of(), 0,
                    elapsed(start), null);
        } catch (RuntimeException e) {
            log.warn("Saga {} 在第 {} 步失败，开始反向补偿: {}", sagaId, executed.size() + 1, e.getMessage());
            return compensate(sagaId, steps, executed, start, e);
        }
    }

    /**
     * 反向补偿已执行的步骤。
     *
     * <p>单个步骤补偿失败会重试；重试耗尽后记入 {@code failedCompensations} 并<b>继续补偿后续步骤</b>，
     * 避免一个卡住的补偿把整个回滚流程堵死。
     *
     * @param sagaId   事务标识
     * @param steps    全量步骤，用于按名字找回补偿动作
     * @param executed 已成功执行的正向步骤名（按执行顺序）
     * @param start    起始纳秒，用于统计耗时
     * @param cause    触发补偿的原始异常
     * @return 补偿结果
     */
    private SagaRun compensate(String sagaId, List<SagaStep> steps, List<String> executed,
                               long start, RuntimeException cause) {
        List<String> compensated = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int retries = 0;
        // 必须倒序：后面的步骤依赖前面步骤的结果，先撤后面的才不会留下悬空引用
        for (int i = executed.size() - 1; i >= 0; i--) {
            String name = executed.get(i);
            SagaStep step = find(steps, name);
            int attempts = 0;
            RuntimeException lastError = null;
            while (attempts < maxCompensationAttempts) {
                attempts++;
                try {
                    step.compensation().run();
                    lastError = null;
                    log.info("【Saga】补偿成功: sagaId={}, step={}, 第 {} 次尝试", sagaId, name, attempts);
                    break;
                } catch (RuntimeException e) {
                    lastError = e;
                    log.warn("Saga {} 补偿步骤 {} 第 {} 次失败: {}", sagaId, name, attempts, e.getMessage());
                }
            }
            retries += attempts - 1;
            if (lastError == null) {
                compensated.add(name);
            } else {
                // 补偿失败不能吞掉：留在结果里交给对账/人工，其他步骤继续补偿。
                failed.add(name);
                log.error("【Saga】补偿最终失败，需人工介入: sagaId={}, step={}, 已尝试 {} 次, error={}",
                        sagaId, name, attempts, lastError.getMessage());
            }
        }
        log.info("【Saga】补偿结束: sagaId={}, 成功 {} 步, 失败 {} 步, 重试 {} 次",
                sagaId, compensated.size(), failed.size(), retries);
        return new SagaRun(false, List.copyOf(executed), List.copyOf(compensated), List.copyOf(failed),
                retries, elapsed(start), cause.getMessage());
    }

    /**
     * 按步骤名找 Step 定义。名字是补偿时唯一的索引，所以步骤名必须唯一。
     */
    private static SagaStep find(List<SagaStep> steps, String name) {
        return steps.stream()
                .filter(step -> step.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("步骤不存在: " + name));
    }

    private static long elapsed(long startNanos) {
        return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startNanos);
    }
}
