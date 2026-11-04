package com.company.service.archlab.scenario.idempotency;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 幂等实验：同一个订单号被并发提交 N 次，比较四种防护方式的"真实扣款次数"。
 *
 * <p>【解决什么问题】用户点两下提交、网关超时后客户端重试、MQ 至少一次投递、定时任务重跑，
 * 都会让同一个业务请求被执行多次。如果下单/扣款接口不幂等，就会重复扣库存、重复记账，
 * 这类问题在日志里看起来每个请求都"成功"，只有对账时才会发现钱多了或货少了。
 *
 * <p>【为什么这样设计】这里把四种常见方案放在同一个并发场景下对照，而不是只讲结论：
 * <ul>
 *   <li>{@code TOKEN}：请求级防重，客户端先取令牌，服务端用 SETNX 抢占；实现简单，
 *       但令牌可能丢失、也可能被绕过，只能挡住"同一次提交"；</li>
 *   <li>{@code UNIQUE_INDEX}：数据级兜底，用业务唯一键（订单号）建唯一索引，冲突即拒绝；
 *       这是最后一道防线，即使上层漏了也能保证不重复落库；</li>
 *   <li>{@code STATE_MACHINE}：业务状态级幂等，只允许 CREATED → PAID 迁移一次，
 *       重复回调直接返回成功——它不是"拒绝重复"，而是"重复执行也不产生副作用"；</li>
 *   <li>{@code NONE}：裸接口，用来量化"不加防护会重复执行多少次"。</li>
 * </ul>
 * 用 {@link ConcurrentHashMap#putIfAbsent} 模拟 Redis 的 SETNX，用
 * {@link AtomicReference#compareAndSet} 模拟数据库的条件更新，都是为了让实验不依赖中间件。
 *
 * <p>【怎么读这段代码】先看 {@link Mode} 的四个枚举，再按 {@link #submit} 里的 switch 分支
 * 对照它们的并发写法；最后看三个指标：
 * <ul>
 *   <li>{@code businessExecutions}：真正执行了下单/扣款逻辑的次数，必须等于 1；</li>
 *   <li>{@code successResponses}：对外返回成功的次数。状态机方案会对重复回调返回成功（幂等应答），
 *       所以它大于 1 并不代表重复扣款；</li>
 *   <li>{@code rejected}：被明确拒绝的次数，客户端应据此提示"请勿重复提交"。</li>
 * </ul>
 *
 * <p>生产上这四种不是互斥的：令牌防重放、唯一索引兜底、状态机保证状态只能前进一次，
 * 三者叠起来才是完整方案。单靠任何一层都有漏洞（令牌丢失、索引建错、状态被重置）。
 */
@Service
public class IdempotencyLab {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyLab.class);

    /** 固定订单号：所有并发请求都拿它提交，模拟"同一个订单被重复提交"。 */
    private static final String ORDER_NO = "ORDER-20261027-0001";

    public enum Mode {

        /** 裸接口：来多少次执行多少次 */
        NONE,

        /** 客户端先取令牌，服务端用 SETNX 抢占令牌（这里用 ConcurrentHashMap 模拟） */
        TOKEN,

        /** 用业务唯一键（订单号）建唯一索引，冲突即拒绝 */
        UNIQUE_INDEX,

        /** 状态机：只允许 CREATED → PAID 迁移一次，重复回调幂等返回 */
        STATE_MACHINE
    }

    /**
     * @param businessExecutions 真正执行业务动作的次数，这是判断是否重复扣款的唯一指标
     * @param successResponses   返回成功的次数；状态机模式下重复请求也会成功
     * @param rejected           被拒绝的次数
     */
    public record Result(String mode,
                         int requests,
                         int businessExecutions,
                         int successResponses,
                         int rejected,
                         long elapsedMillis) {
    }

    private enum Status {
        CREATED, PAID
    }

    /**
     * 并发提交同一个订单，统计真实执行次数。
     *
     * @param mode              防护方式，不能为空
     * @param concurrentRequests 并发提交次数，范围 [1, 200]
     * @return 业务执行次数、成功应答数与拒绝数
     * @throws IllegalArgumentException mode 为空或并发数越界时抛出
     * @throws IllegalStateException    实验等待超时或被中断时抛出
     */
    public Result submit(Mode mode, int concurrentRequests) {
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空");
        }
        if (concurrentRequests < 1 || concurrentRequests > 200) {
            throw new IllegalArgumentException("requests 必须在 [1, 200] 之间");
        }

        log.info("【幂等实验】开始，mode={}，订单号={}，并发提交={}", mode, ORDER_NO, concurrentRequests);
        // 下面三个容器分别模拟：Redis 令牌、数据库唯一索引、业务状态行。
        ConcurrentHashMap<String, Boolean> tokens = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Boolean> orderIndex = new ConcurrentHashMap<>();
        AtomicReference<Status> status = new AtomicReference<>(Status.CREATED);
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        // 用两个闩锁控制起跑与收尾：所有线程先等 start，最后一起减 done。
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrentRequests);
        ExecutorService pool = Executors.newFixedThreadPool(concurrentRequests);
        long startNanos = System.nanoTime();
        try {
            for (int i = 0; i < concurrentRequests; i++) {
                pool.execute(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        switch (mode) {
                            case NONE -> {
                                // 没有防护：每个请求都执行一次业务动作。
                                executeOnce(executions);
                                success.incrementAndGet();
                            }
                            case TOKEN -> {
                                // putIfAbsent 等价于 Redis SETNX：只有第一个请求能占住令牌，
                                // 其余请求看到已存在，直接拒绝。
                                if (tokens.putIfAbsent(ORDER_NO, Boolean.TRUE) == null) {
                                    executeOnce(executions);
                                    success.incrementAndGet();
                                } else {
                                    rejected.incrementAndGet();
                                }
                            }
                            case UNIQUE_INDEX -> {
                                // 唯一索引的语义：同一订单号只允许插入一次，冲突即拒绝。
                                // 它比令牌更可靠，因为约束在数据层，不依赖调用方配合。
                                if (orderIndex.putIfAbsent(ORDER_NO, Boolean.TRUE) == null) {
                                    executeOnce(executions);
                                    success.incrementAndGet();
                                } else {
                                    rejected.incrementAndGet();
                                }
                            }
                            case STATE_MACHINE -> {
                                // CAS 就是"带版本号的条件更新"：只有状态还是 CREATED 的线程能迁移成功。
                                if (status.compareAndSet(Status.CREATED, Status.PAID)) {
                                    executeOnce(executions);
                                    success.incrementAndGet();
                                } else if (status.get() == Status.PAID) {
                                    // 重复回调：已经处理过，直接幂等返回成功，不能再扣一次
                                    success.incrementAndGet();
                                } else {
                                    rejected.incrementAndGet();
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        // 恢复中断标记，让线程池关闭时能正确退出。
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            // 所有线程已就绪，放行，让它们尽可能同时竞争同一份共享状态。
            start.countDown();
            if (!done.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("幂等实验超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("幂等实验被中断", e);
        } finally {
            pool.shutdownNow();
        }

        Result result = new Result(mode.name(),
                concurrentRequests,
                executions.get(),
                success.get(),
                rejected.get(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
        log.info("【幂等实验】结束，mode={}，请求={}，真实执行业务={} 次，成功应答={}，拒绝={}，耗时={}ms",
                mode, concurrentRequests, result.businessExecutions(), result.successResponses(),
                result.rejected(), result.elapsedMillis());
        if (result.businessExecutions() > 1) {
            log.warn("【幂等实验】{} 模式下业务被执行了 {} 次，真实环境就是重复扣款",
                    mode, result.businessExecutions());
        }
        return result;
    }

    /**
     * 模拟真正有副作用的业务动作（扣库存、记账）。次数由调用方统计。
     */
    private void executeOnce(AtomicInteger executions) {
        executions.incrementAndGet();
    }
}
