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
import com.company.service.archlab.cache.CacheLab;

/**
 * 缓存实验入口：默认参数会跑"出问题的写法"，把参数改一下就能看到治理后的数字。
 *
 * <p>【解决什么问题】实验代码写好了，但如果没有一个统一的出入口，
 * 学习者只能靠改代码、跑单测来验证结论，既不方便对比，也容易改坏实验。
 * 这个 Controller 把四类缓存问题暴露成 HTTP 接口，参数就是实验变量。
 *
 * <p>【为什么这样设计】默认参数刻意选择"会出问题"的取值，
 * 这样打开接口就能看到坏结果；只改一个参数（如 {@code mode=BLOOM}）就能看到治理后的数字，
 * 两组结果用的是同一套代码和同一套指标，对照才可信。
 * 参数错误统一翻译成 {@code PARAM_INVALID}，而不是抛 500——这是调用方的问题，不是系统故障。
 *
 * <p>【怎么读这段代码】先看每个 {@code @GetMapping} 的默认值，那是"问题现场"；
 * 再按类注释里的对照顺序依次请求，比较返回体里的 {@code dbLoads}。
 * Controller 只做参数透传和错误翻译，实验逻辑都在 {@link CacheLab} 里。
 */
@RestController
@RequestMapping("/cache")
public class CacheController {

    private static final Logger log = LoggerFactory.getLogger(CacheController.class);

    private final CacheLab cacheLab;

    public CacheController(CacheLab cacheLab) {
        this.cacheLab = cacheLab;
    }

    /**
     * 穿透实验，例如：{@code ?requests=100&mode=BLOOM}。
     *
     * @param requests 请求次数，默认 100
     * @param mode     防护策略，默认 NAIVE（不防护）
     * @return 回源次数与布隆拦截次数
     */
    @GetMapping("/penetration")
    public ApiResult<CacheLab.PenetrationResult> penetration(
            @RequestParam(name = "requests", defaultValue = "100") int requests,
            @RequestParam(name = "mode", defaultValue = "NAIVE") CacheLab.PenetrationMode mode) {
        log.info("【缓存实验接口】/cache/penetration 收到请求，requests={}，mode={}", requests, mode);
        return run(() -> cacheLab.penetration(requests, mode));
    }

    /**
     * 击穿实验，例如：{@code ?threads=16&loadMillis=50&mode=SINGLE_FLIGHT}。
     *
     * @param threads    并发线程数，默认 16
     * @param loadMillis 单次查库模拟耗时，默认 50ms
     * @param mode       是否启用单飞，默认 NAIVE
     * @return 实际回源次数与耗时
     */
    @GetMapping("/breakdown")
    public ApiResult<CacheLab.BreakdownResult> breakdown(
            @RequestParam(name = "threads", defaultValue = "16") int threads,
            @RequestParam(name = "loadMillis", defaultValue = "50") long loadMillis,
            @RequestParam(name = "mode", defaultValue = "NAIVE") CacheLab.BreakdownMode mode) {
        log.info("【缓存实验接口】/cache/breakdown 收到请求，threads={}，loadMillis={}，mode={}",
                threads, loadMillis, mode);
        return run(() -> cacheLab.breakdown(threads, loadMillis, mode));
    }

    /**
     * 雪崩实验，例如：{@code ?keys=50&baseTtlMillis=300&jitter=true}。
     *
     * @param keys          预热 key 数，默认 50
     * @param baseTtlMillis 基础 TTL，默认 300ms
     * @param jitter        是否给 TTL 加抖动，默认 false
     * @return 过期后的回源次数
     */
    @GetMapping("/avalanche")
    public ApiResult<CacheLab.AvalancheResult> avalanche(
            @RequestParam(name = "keys", defaultValue = "50") int keys,
            @RequestParam(name = "baseTtlMillis", defaultValue = "300") long baseTtlMillis,
            @RequestParam(name = "jitter", defaultValue = "false") boolean jitter) {
        log.info("【缓存实验接口】/cache/avalanche 收到请求，keys={}，baseTtlMillis={}，jitter={}",
                keys, baseTtlMillis, jitter);
        return run(() -> cacheLab.avalanche(keys, baseTtlMillis, jitter));
    }

    /**
     * 热点实验，例如：{@code ?requests=1000&l1Enabled=true}。
     *
     * @param requests  请求次数，默认 1000
     * @param l1Enabled 是否开启 L1，默认 true
     * @return L1/L2 命中结构与命中率
     */
    @GetMapping("/hot-key")
    public ApiResult<CacheLab.HotKeyResult> hotKey(
            @RequestParam(name = "requests", defaultValue = "1000") int requests,
            @RequestParam(name = "l1Enabled", defaultValue = "true") boolean l1Enabled) {
        log.info("【缓存实验接口】/cache/hot-key 收到请求，requests={}，l1Enabled={}", requests, l1Enabled);
        return run(() -> cacheLab.hotKey(requests, l1Enabled));
    }

    /**
     * 与并发、设计模式实验台一致：把参数问题翻译成 40001。
     */
    private <T> ApiResult<T> run(Supplier<T> experiment) {
        try {
            return ApiResult.ok(experiment.get());
        } catch (IllegalArgumentException e) {
            // 参数越界/枚举解析失败属于调用方错误，用 warn 留下入参线索，不要打成 error。
            log.warn("【缓存实验接口】参数非法：{}", e.getMessage());
            throw new BizException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
    }
}
