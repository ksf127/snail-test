package com.company.service.archlab.controller;

import java.util.function.Supplier;

import com.company.common.core.ApiResult;
import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.service.archlab.scenario.consistency.ConsistencyLab;
import com.company.service.archlab.scenario.idempotency.IdempotencyLab;
import com.company.service.archlab.scenario.lock.LockLab;
import com.company.service.archlab.scenario.rateLimit.RateLimitLab;
import com.company.service.archlab.scenario.stock.StockLab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第二阶段场景实验入口：缓存一致性、幂等、限流、库存超卖、分布式锁。
 *
 * <p>与缓存实验一致，默认参数跑"会出问题的写法"，改参数就能看到治理后的数字。
 * 建议对照：
 * <ul>
 *   <li>{@code /cache-consistency?strategy=UPDATE_DB_THEN_DELETE_CACHE} vs {@code LOCK_PROTECTED}</li>
 *   <li>{@code /idempotency?mode=NONE} vs {@code TOKEN} / {@code STATE_MACHINE}</li>
 *   <li>{@code /rate-limit?mode=FIXED_WINDOW} vs {@code SLIDING_WINDOW}</li>
 *   <li>{@code /oversell?mode=NONE} vs {@code LOCAL_LOCK} vs {@code OPTIMISTIC}</li>
 * </ul>
 */
@RestController
@RequestMapping("/scenario")
public class ScenarioController {

    private final ConsistencyLab consistencyLab;

    private final IdempotencyLab idempotencyLab;

    private final RateLimitLab rateLimitLab;

    private final StockLab stockLab;

    private final LockLab lockLab;

    public ScenarioController(ConsistencyLab consistencyLab,
                              IdempotencyLab idempotencyLab,
                              RateLimitLab rateLimitLab,
                              StockLab stockLab,
                              LockLab lockLab) {
        this.consistencyLab = consistencyLab;
        this.idempotencyLab = idempotencyLab;
        this.rateLimitLab = rateLimitLab;
        this.stockLab = stockLab;
        this.lockLab = lockLab;
    }

    /**
     * 缓存与数据库一致性，例如：{@code ?strategy=DELAYED_DOUBLE_DELETE&rounds=100}。
     */
    @GetMapping("/cache-consistency")
    public ApiResult<ConsistencyLab.Result> cacheConsistency(
            @RequestParam(name = "strategy", defaultValue = "UPDATE_DB_THEN_DELETE_CACHE")
            ConsistencyLab.Strategy strategy,
            @RequestParam(name = "rounds", defaultValue = "100") int rounds) {
        return run(() -> consistencyLab.run(strategy, rounds));
    }

    /**
     * 幂等，例如：{@code ?mode=TOKEN&requests=50}。
     */
    @GetMapping("/idempotency")
    public ApiResult<IdempotencyLab.Result> idempotency(
            @RequestParam(name = "mode", defaultValue = "NONE") IdempotencyLab.Mode mode,
            @RequestParam(name = "requests", defaultValue = "50") int requests) {
        return run(() -> idempotencyLab.submit(mode, requests));
    }

    /**
     * 限流，例如：{@code ?mode=SLIDING_WINDOW&limit=10&windowMillis=1000}。
     */
    @GetMapping("/rate-limit")
    public ApiResult<RateLimitLab.Result> rateLimit(
            @RequestParam(name = "mode", defaultValue = "FIXED_WINDOW") RateLimitLab.Mode mode,
            @RequestParam(name = "limit", defaultValue = "10") int limit,
            @RequestParam(name = "windowMillis", defaultValue = "1000") long windowMillis) {
        return run(() -> rateLimitLab.simulate(mode, limit, windowMillis, null));
    }

    /**
     * 库存超卖，例如：{@code ?mode=LOCAL_LOCK&stock=1&buyers=2&instances=2}。
     */
    @GetMapping("/oversell")
    public ApiResult<StockLab.Result> oversell(
            @RequestParam(name = "mode", defaultValue = "NONE") StockLab.Mode mode,
            @RequestParam(name = "stock", defaultValue = "10") int stock,
            @RequestParam(name = "buyers", defaultValue = "20") int buyers,
            @RequestParam(name = "instances", defaultValue = "2") int instances) {
        return run(() -> stockLab.run(mode, stock, buyers, instances));
    }

    /**
     * 分布式锁计数，例如：{@code ?locked=true&threads=8&iterations=100}。
     */
    @GetMapping("/lock-counter")
    public ApiResult<LockLab.Result> lockCounter(
            @RequestParam(name = "locked", defaultValue = "true") boolean locked,
            @RequestParam(name = "threads", defaultValue = "8") int threads,
            @RequestParam(name = "iterations", defaultValue = "100") int iterations) {
        return run(() -> lockLab.counter(locked, threads, iterations));
    }

    private <T> ApiResult<T> run(Supplier<T> experiment) {
        try {
            return ApiResult.ok(experiment.get());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
    }
}
