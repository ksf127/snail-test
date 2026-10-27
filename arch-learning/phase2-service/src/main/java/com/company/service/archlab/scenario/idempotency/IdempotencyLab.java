package com.company.service.archlab.scenario.idempotency;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

/**
 * 幂等实验：同一个订单号被并发提交 N 次，比较四种防护方式的"真实扣款次数"。
 *
 * <p>四个数字要分开看：
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

    public Result submit(Mode mode, int concurrentRequests) {
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空");
        }
        if (concurrentRequests < 1 || concurrentRequests > 200) {
            throw new IllegalArgumentException("requests 必须在 [1, 200] 之间");
        }

        ConcurrentHashMap<String, Boolean> tokens = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Boolean> orderIndex = new ConcurrentHashMap<>();
        AtomicReference<Status> status = new AtomicReference<>(Status.CREATED);
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

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
                                executeOnce(executions);
                                success.incrementAndGet();
                            }
                            case TOKEN -> {
                                if (tokens.putIfAbsent(ORDER_NO, Boolean.TRUE) == null) {
                                    executeOnce(executions);
                                    success.incrementAndGet();
                                } else {
                                    rejected.incrementAndGet();
                                }
                            }
                            case UNIQUE_INDEX -> {
                                if (orderIndex.putIfAbsent(ORDER_NO, Boolean.TRUE) == null) {
                                    executeOnce(executions);
                                    success.incrementAndGet();
                                } else {
                                    rejected.incrementAndGet();
                                }
                            }
                            case STATE_MACHINE -> {
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
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
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

        return new Result(mode.name(),
                concurrentRequests,
                executions.get(),
                success.get(),
                rejected.get(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    }

    /**
     * 模拟真正有副作用的业务动作（扣库存、记账）。次数由调用方统计。
     */
    private void executeOnce(AtomicInteger executions) {
        executions.incrementAndGet();
    }
}
