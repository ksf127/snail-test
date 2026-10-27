package com.company.service.archlab.controller;

import java.util.function.Supplier;

import com.company.common.core.ApiResult;
import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.service.archlab.cache.CacheLab;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 缓存实验入口：默认参数会跑"出问题的写法"，把参数改一下就能看到治理后的数字。
 *
 * <p>建议对照顺序：{@code /penetration?mode=NAIVE} 与 {@code mode=BLOOM}、
 * {@code /breakdown?mode=NAIVE} 与 {@code mode=SINGLE_FLIGHT}。
 */
@RestController
@RequestMapping("/cache")
public class CacheController {

    private final CacheLab cacheLab;

    public CacheController(CacheLab cacheLab) {
        this.cacheLab = cacheLab;
    }

    /**
     * 穿透实验，例如：{@code ?requests=100&mode=BLOOM}。
     */
    @GetMapping("/penetration")
    public ApiResult<CacheLab.PenetrationResult> penetration(
            @RequestParam(name = "requests", defaultValue = "100") int requests,
            @RequestParam(name = "mode", defaultValue = "NAIVE") CacheLab.PenetrationMode mode) {
        return run(() -> cacheLab.penetration(requests, mode));
    }

    /**
     * 击穿实验，例如：{@code ?threads=16&loadMillis=50&mode=SINGLE_FLIGHT}。
     */
    @GetMapping("/breakdown")
    public ApiResult<CacheLab.BreakdownResult> breakdown(
            @RequestParam(name = "threads", defaultValue = "16") int threads,
            @RequestParam(name = "loadMillis", defaultValue = "50") long loadMillis,
            @RequestParam(name = "mode", defaultValue = "NAIVE") CacheLab.BreakdownMode mode) {
        return run(() -> cacheLab.breakdown(threads, loadMillis, mode));
    }

    /**
     * 雪崩实验，例如：{@code ?keys=50&baseTtlMillis=300&jitter=true}。
     */
    @GetMapping("/avalanche")
    public ApiResult<CacheLab.AvalancheResult> avalanche(
            @RequestParam(name = "keys", defaultValue = "50") int keys,
            @RequestParam(name = "baseTtlMillis", defaultValue = "300") long baseTtlMillis,
            @RequestParam(name = "jitter", defaultValue = "false") boolean jitter) {
        return run(() -> cacheLab.avalanche(keys, baseTtlMillis, jitter));
    }

    /**
     * 热点实验，例如：{@code ?requests=1000&l1Enabled=true}。
     */
    @GetMapping("/hot-key")
    public ApiResult<CacheLab.HotKeyResult> hotKey(
            @RequestParam(name = "requests", defaultValue = "1000") int requests,
            @RequestParam(name = "l1Enabled", defaultValue = "true") boolean l1Enabled) {
        return run(() -> cacheLab.hotKey(requests, l1Enabled));
    }

    /**
     * 与并发、设计模式实验台一致：把参数问题翻译成 40001。
     */
    private <T> ApiResult<T> run(Supplier<T> experiment) {
        try {
            return ApiResult.ok(experiment.get());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
    }
}
