package com.company.service.archlab.controller;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.company.common.core.ApiResult;
import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.service.archlab.scenario.consistency.ConsistencyLab;
import com.company.service.archlab.scenario.idempotency.IdempotencyLab;
import com.company.service.archlab.scenario.lock.LockLab;
import com.company.service.archlab.scenario.rateLimit.RateLimitLab;
import com.company.service.archlab.scenario.stock.StockLab;

/**
 * 第二阶段场景实验入口：缓存一致性、幂等、限流、库存超卖、分布式锁。
 *
 * <p>【解决什么问题】这五类问题都是"单体应用跑得好好的，一上集群/一遇并发就出问题"：
 * 缓存与数据库双写不一致、重复请求重复扣款、窗口边界被刷穿、多实例超卖、
 * 分布式锁用错导致临界区失效。没有可运行的对照实验，很难真正理解每种方案在防什么。
 *
 * <p>【为什么这样设计】与缓存实验一致，默认参数跑"会出问题的写法"，改参数就能看到治理后的数字。
 * 每个接口返回的都包含"问题指标"（如脏轮数、真实扣款次数、被拒请求数、超卖数），
 * 而不是只返回一个成功标记——这样两组实验可以直接用数字对比。
 * 参数非法统一翻译成 {@code PARAM_INVALID}，避免 500 被误判成系统故障。
 *
 * <p>【怎么读这段代码】先对照类注释里的四组接口，每组看两个模式：
 * 默认模式是"没有防护"，另一个模式是"加了防护"；返回体里的关键指标在对应 Lab 的类注释中都有解释。
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

    private static final Logger log = LoggerFactory.getLogger(ScenarioController.class);

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
     *
     * @param strategy 写路径策略，默认先更库后删缓存
     * @param rounds   重复轮数，默认 100
     * @return 脏轮数、脏回填轮数以及最后一轮的时序
     */
    @GetMapping("/cache-consistency")
    public ApiResult<ConsistencyLab.Result> cacheConsistency(
            @RequestParam(name = "strategy", defaultValue = "UPDATE_DB_THEN_DELETE_CACHE")
            ConsistencyLab.Strategy strategy,
            @RequestParam(name = "rounds", defaultValue = "100") int rounds) {
        log.info("【场景实验接口】/scenario/cache-consistency 收到请求，strategy={}，rounds={}",
                strategy, rounds);
        return run(() -> consistencyLab.run(strategy, rounds));
    }

    /**
     * 幂等，例如：{@code ?mode=TOKEN&requests=50}。
     *
     * @param mode     防护方式，默认 NONE
     * @param requests 并发提交次数，默认 50
     * @return 真实业务执行次数、成功应答数与拒绝数
     */
    @GetMapping("/idempotency")
    public ApiResult<IdempotencyLab.Result> idempotency(
            @RequestParam(name = "mode", defaultValue = "NONE") IdempotencyLab.Mode mode,
            @RequestParam(name = "requests", defaultValue = "50") int requests) {
        log.info("【场景实验接口】/scenario/idempotency 收到请求，mode={}，requests={}", mode, requests);
        return run(() -> idempotencyLab.submit(mode, requests));
    }

    /**
     * 限流，例如：{@code ?mode=SLIDING_WINDOW&limit=10&windowMillis=1000}。
     *
     * @param mode         限流算法，默认固定窗口
     * @param limit        窗口内/桶内允许的请求数，默认 10
     * @param windowMillis 窗口长度，默认 1000ms
     * @return 放行/拒绝数量与放行时刻
     */
    @GetMapping("/rate-limit")
    public ApiResult<RateLimitLab.Result> rateLimit(
            @RequestParam(name = "mode", defaultValue = "FIXED_WINDOW") RateLimitLab.Mode mode,
            @RequestParam(name = "limit", defaultValue = "10") int limit,
            @RequestParam(name = "windowMillis", defaultValue = "1000") long windowMillis) {
        log.info("【场景实验接口】/scenario/rate-limit 收到请求，mode={}，limit={}，windowMillis={}",
                mode, limit, windowMillis);
        // 传 null 让 Lab 使用默认的"窗口边界两侧各来一批"请求序列，专门暴露边界漏洞。
        return run(() -> rateLimitLab.simulate(mode, limit, windowMillis, null));
    }

    /**
     * 库存超卖，例如：{@code ?mode=LOCAL_LOCK&stock=1&buyers=2&instances=2}。
     *
     * @param mode      扣减方式，默认 NONE
     * @param stock     初始库存，默认 10
     * @param buyers    买家数，默认 20
     * @param instances 模拟实例数，默认 2
     * @return 已售、剩余、超卖数以及恒等式是否成立
     */
    @GetMapping("/oversell")
    public ApiResult<StockLab.Result> oversell(
            @RequestParam(name = "mode", defaultValue = "NONE") StockLab.Mode mode,
            @RequestParam(name = "stock", defaultValue = "10") int stock,
            @RequestParam(name = "buyers", defaultValue = "20") int buyers,
            @RequestParam(name = "instances", defaultValue = "2") int instances) {
        log.info("【场景实验接口】/scenario/oversell 收到请求，mode={}，stock={}，buyers={}，instances={}",
                mode, stock, buyers, instances);
        return run(() -> stockLab.run(mode, stock, buyers, instances));
    }

    /**
     * 分布式锁计数，例如：{@code ?locked=true&threads=8&iterations=100}。
     *
     * @param locked     true 走分布式锁，false 裸自增
     * @param threads    并发线程数，默认 8
     * @param iterations 每线程自增次数，默认 100
     * @return 期望值、实际值与耗时
     */
    @GetMapping("/lock-counter")
    public ApiResult<LockLab.Result> lockCounter(
            @RequestParam(name = "locked", defaultValue = "true") boolean locked,
            @RequestParam(name = "threads", defaultValue = "8") int threads,
            @RequestParam(name = "iterations", defaultValue = "100") int iterations) {
        log.info("【场景实验接口】/scenario/lock-counter 收到请求，locked={}，threads={}，iterations={}",
                locked, threads, iterations);
        return run(() -> lockLab.counter(locked, threads, iterations));
    }

    private <T> ApiResult<T> run(Supplier<T> experiment) {
        try {
            return ApiResult.ok(experiment.get());
        } catch (IllegalArgumentException e) {
            // 参数越界属于调用方错误：warn 记录原因，翻译成业务错误码，而不是 500。
            log.warn("【场景实验接口】参数非法：{}", e.getMessage());
            throw new BizException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
    }
}
