package com.company.service.archlab.controller;

import java.util.List;
import java.util.function.Supplier;

import com.company.common.core.ApiResult;
import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.service.archlab.concurrency.CounterComparison;
import com.company.service.archlab.concurrency.VirtualThreadComparison;
import com.company.service.archlab.concurrency.VisibilityDemo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 并发实验入口：每个接口都能直接跑出可对照的数字。
 *
 * <p>注意：这些接口是"实验台"，参数都做了上限，避免一个 curl 把本地机器打挂。
 */
@RestController
@RequestMapping("/concurrency")
public class ConcurrencyController {

    private final CounterComparison counterComparison;

    private final VirtualThreadComparison virtualThreadComparison;

    private final VisibilityDemo visibilityDemo;

    public ConcurrencyController(CounterComparison counterComparison,
                                 VirtualThreadComparison virtualThreadComparison,
                                 VisibilityDemo visibilityDemo) {
        this.counterComparison = counterComparison;
        this.virtualThreadComparison = virtualThreadComparison;
        this.visibilityDemo = visibilityDemo;
    }

    /**
     * 单策略计数器压测，例如：{@code ?strategy=LONG_ADDER&threads=8&increments=1000000}。
     */
    @GetMapping("/counter")
    public ApiResult<CounterComparison.Result> counter(
            @RequestParam(name = "strategy") CounterComparison.Strategy strategy,
            @RequestParam(name = "threads", defaultValue = "8") int threads,
            @RequestParam(name = "increments", defaultValue = "1000000") long increments) {
        return run(() -> counterComparison.run(strategy, threads, increments));
    }

    /**
     * 五种写法一起跑，直接看"正确性 + 耗时"的差异。
     */
    @GetMapping("/counter/all")
    public ApiResult<List<CounterComparison.Result>> counterAll(
            @RequestParam(name = "threads", defaultValue = "8") int threads,
            @RequestParam(name = "increments", defaultValue = "200000") long increments) {
        return run(() -> counterComparison.runAll(threads, increments));
    }

    /**
     * 平台线程池 vs 虚拟线程：默认 2000 个任务、每个阻塞 50ms。
     * 平台池设为 200 时，理论耗时 ≈ (2000 / 200) * 50ms = 500ms，虚拟线程 ≈ 50ms 量级。
     */
    @GetMapping("/threads")
    public ApiResult<VirtualThreadComparison.Comparison> threads(
            @RequestParam(name = "tasks", defaultValue = "2000") int tasks,
            @RequestParam(name = "ioMillis", defaultValue = "50") long ioMillis,
            @RequestParam(name = "poolSize", defaultValue = "200") int poolSize) {
        return run(() -> virtualThreadComparison.compare(tasks, ioMillis, poolSize));
    }

    /**
     * 可见性实验：{@code useVolatile=false} 时，观察读线程是否能及时看到写线程的更新。
     */
    @GetMapping("/visibility")
    public ApiResult<VisibilityDemo.Result> visibility(
            @RequestParam(name = "useVolatile", defaultValue = "false") boolean useVolatile,
            @RequestParam(name = "timeoutMillis", defaultValue = "1000") long timeoutMillis) {
        return run(() -> visibilityDemo.run(useVolatile, timeoutMillis));
    }

    /**
     * 把实验台内部的参数校验失败翻译成 40001，而不是统一 50000。
     */
    private <T> ApiResult<T> run(Supplier<T> experiment) {
        try {
            return ApiResult.ok(experiment.get());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
    }
}
