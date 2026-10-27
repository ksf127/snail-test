package com.company.service.archlab.scenario.distributedtx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seata Saga 的最小可运行复刻：<b>正向顺序执行 + 失败时反向补偿</b>。
 *
 * <p>Saga 和 AT 的取舍正好相反：
 * <ul>
 *   <li>AT 靠全局锁把中间态藏起来；Saga 没有锁，也没有隔离性，
 *       中间态对任何读方都可见（例如"订单已创建、库存还没扣"）。</li>
 *   <li>AT 回滚依据 undo log 的镜像；Saga 回滚依据<b>每个正向步骤配一个补偿动作</b>，
 *       所以补偿必须由业务自己写，而且必须幂等 —— 补偿请求可能重复到达。</li>
 *   <li>补偿也可能失败（下游宕机、超时），因此必须有重试上限，
 *       超过上限就记为"补偿失败"交给人工/对账兜底，绝不能静默丢弃。</li>
 * </ul>
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

    public SagaOrchestrator(int maxCompensationAttempts) {
        if (maxCompensationAttempts < 1) {
            throw new IllegalArgumentException("maxCompensationAttempts 必须大于 0");
        }
        this.maxCompensationAttempts = maxCompensationAttempts;
    }

    /**
     * 顺序执行所有步骤；在 {@code failAt} 处抛错，然后对已执行步骤反向补偿。
     *
     * @param failAt 第几个步骤失败（0 基），-1 表示全部成功
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
        try {
            for (int i = 0; i < steps.size(); i++) {
                if (i == failAt) {
                    throw new IllegalStateException("模拟第 " + (i + 1) + " 个步骤失败");
                }
                SagaStep step = steps.get(i);
                step.action().run();
                executed.add(step.name());
            }
            return new SagaRun(true, List.copyOf(executed), List.of(), List.of(), 0,
                    elapsed(start), null);
        } catch (RuntimeException e) {
            log.warn("Saga {} 在第 {} 步失败，开始反向补偿: {}", sagaId, executed.size() + 1, e.getMessage());
            return compensate(sagaId, steps, executed, start, e);
        }
    }

    private SagaRun compensate(String sagaId, List<SagaStep> steps, List<String> executed,
                               long start, RuntimeException cause) {
        List<String> compensated = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int retries = 0;
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
            }
        }
        return new SagaRun(false, List.copyOf(executed), List.copyOf(compensated), List.copyOf(failed),
                retries, elapsed(start), cause.getMessage());
    }

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
