package com.company.service.archlab.controller;

import java.util.List;
import java.util.function.Supplier;

import com.company.common.core.ApiResult;
import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.service.archlab.concurrency.CounterComparison;
import com.company.service.archlab.concurrency.VirtualThreadComparison;
import com.company.service.archlab.concurrency.VisibilityDemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 并发实验入口：每个接口都能直接跑出可对照的数字。
 *
 * <p>【解决什么问题】让学习者不写 main、不搭 JMH，用一条 curl 就能把并发概念跑出来。
 * 同时把实验参数和边界校验统一收口，避免"一个请求把本地机器打挂"。
 *
 * <p>【为什么这样设计】实验逻辑全部放在 Service 里，Controller 只做"接参 → 调服务 → 包结果"。
 * 这样同一段实验既能被 HTTP 触发，也能被单元测试直接调用；参数上限也由 Service 统一守住，
 * 不依赖调用方自觉。Controller 里额外只做一件事：把参数错误翻译成 40001。
 *
 * <p>【怎么读这段代码】按"端点 → Service"逐个看：
 * counter / counterAll → CounterComparison，threads → VirtualThreadComparison，
 * visibility → VisibilityDemo。所有端点共用的异常翻译在最后的 {@link #run(Supplier)}。
 */
@RestController
@RequestMapping("/concurrency")
public class ConcurrencyController {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyController.class);

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
     *
     * @param strategy   同步策略枚举，Spring 会自动按枚举名转换
     * @param threads    并发线程数
     * @param increments 每个线程自增次数
     * @return 本次实验的正确性、耗时与吞吐
     */
    @GetMapping("/counter")
    public ApiResult<CounterComparison.Result> counter(
            @RequestParam(name = "strategy") CounterComparison.Strategy strategy,
            @RequestParam(name = "threads", defaultValue = "8") int threads,
            @RequestParam(name = "increments", defaultValue = "1000000") long increments) {
        // 入参先落日志：压测参数一旦配错（比如 increments 少一个 0），事后只能靠这行复盘
        log.info("【并发实验接口】/counter 收到请求，strategy={}，threads={}，increments={}",
                strategy, threads, increments);
        return run(() -> counterComparison.run(strategy, threads, increments));
    }

    /**
     * 五种写法一起跑，直接看"正确性 + 耗时"的差异。
     *
     * @param threads    并发线程数
     * @param increments 每个线程自增次数
     * @return 五种策略的结果列表，顺序与枚举声明一致
     */
    @GetMapping("/counter/all")
    public ApiResult<List<CounterComparison.Result>> counterAll(
            @RequestParam(name = "threads", defaultValue = "8") int threads,
            @RequestParam(name = "increments", defaultValue = "200000") long increments) {
        log.info("【并发实验接口】/counter/all 收到请求，threads={}，increments={}", threads, increments);
        return run(() -> counterComparison.runAll(threads, increments));
    }

    /**
     * 平台线程池 vs 虚拟线程：默认 2000 个任务、每个阻塞 50ms。
     * 平台池设为 200 时，理论耗时 ≈ (2000 / 200) * 50ms = 500ms，虚拟线程 ≈ 50ms 量级。
     *
     * @param tasks    任务总数
     * @param ioMillis 每个任务模拟的阻塞时长
     * @param poolSize 平台线程池大小
     * @return 两组结果与加速比
     */
    @GetMapping("/threads")
    public ApiResult<VirtualThreadComparison.Comparison> threads(
            @RequestParam(name = "tasks", defaultValue = "2000") int tasks,
            @RequestParam(name = "ioMillis", defaultValue = "50") long ioMillis,
            @RequestParam(name = "poolSize", defaultValue = "200") int poolSize) {
        log.info("【并发实验接口】/threads 收到请求，tasks={}，ioMillis={}，poolSize={}",
                tasks, ioMillis, poolSize);
        return run(() -> virtualThreadComparison.compare(tasks, ioMillis, poolSize));
    }

    /**
     * 可见性实验：{@code useVolatile=false} 时，观察读线程是否能及时看到写线程的更新。
     *
     * <p>注意这个接口在 x86 上测不出差异（循环体不够干净，C2 不会做读提升），
     * 数字差异全是调度噪声。想看真实差异请用 {@link #visibilityHoisting(boolean, long)}。
     *
     * @param useVolatile   是否使用 volatile 字段
     * @param timeoutMillis 读线程最长自旋时间
     * @return 是否观察到新值及自旋次数
     */
    @GetMapping("/visibility")
    public ApiResult<VisibilityDemo.Result> visibility(
            @RequestParam(name = "useVolatile", defaultValue = "false") boolean useVolatile,
            @RequestParam(name = "timeoutMillis", defaultValue = "1000") long timeoutMillis) {
        log.info("【并发实验接口】/visibility 收到请求，useVolatile={}，timeoutMillis={}",
                useVolatile, timeoutMillis);
        return run(() -> visibilityDemo.run(useVolatile, timeoutMillis));
    }

    /**
     * 读提升实验：把循环体压到只剩一次字段读，让 JIT 有机会把读提到循环外。
     *
     * <p>这是能真正看出 volatile 差别的接口：
     * {@code useVolatile=false} 时读线程会一路自旋到上限（写入永远看不到），
     * {@code useVolatile=true} 时主线程一置位它立刻退出。
     *
     * @param useVolatile  是否使用 volatile 字段
     * @param settleMillis 置位前留给 JIT 编译的时间，越短越容易还没编译就置位
     * @return 自旋次数、是否看到写入、耗时
     */
    @GetMapping("/visibility/hoisting")
    public ApiResult<VisibilityDemo.HoistResult> visibilityHoisting(
            @RequestParam(name = "useVolatile", defaultValue = "false") boolean useVolatile,
            @RequestParam(name = "settleMillis", defaultValue = "500") long settleMillis) {
        log.info("【并发实验接口】/visibility/hoisting 收到请求，useVolatile={}，settleMillis={}",
                useVolatile, settleMillis);
        return run(() -> visibilityDemo.runHoisting(useVolatile, settleMillis));
    }

    /**
     * 把实验台内部的参数校验失败翻译成 40001，而不是统一 50000。
     *
     * <p>这里只捕获 IllegalArgumentException：参数错误是可以归因到调用方的，
     * 其他异常（中断、真实 Bug）必须继续往上抛，交给全局处理器按 5xxxx 处理。
     *
     * @param experiment 实际执行实验的代码块
     * @param <T>        实验结果类型
     * @return 包装成统一响应体的实验结果
     * @throws BizException 实验参数非法时抛出，错误码为 PARAM_INVALID
     */
    private <T> ApiResult<T> run(Supplier<T> experiment) {
        try {
            return ApiResult.ok(experiment.get());
        } catch (IllegalArgumentException e) {
            log.warn("【并发实验接口】参数非法，已拒绝执行：{}", e.getMessage());
            throw new BizException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
    }
}
