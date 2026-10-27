package com.company.service.shortlink.web;

import com.company.common.core.ApiResult;
import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.service.shortlink.capacity.CapacityModel;
import com.company.service.shortlink.domain.Shortlink;
import com.company.service.shortlink.service.ShortlinkService;
import com.company.service.shortlink.web.dto.CreateShortlinkRequest;
import com.company.service.shortlink.web.dto.ShortlinkStatsView;
import com.company.service.shortlink.web.dto.ShortlinkView;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理接口：创建 / 查询 / 删除 / 点击量 / 运行计数 / 容量模型。
 *
 * <p>跳转本身不在这个 Controller 里，见 {@link RedirectController}：
 * 跳转是纯 GET + 302，与管理接口的鉴权、限流、审计策略完全不同。
 */
@RestController
@RequestMapping("/links")
public class ShortlinkController {

    private final ShortlinkService shortlinkService;

    public ShortlinkController(ShortlinkService shortlinkService) {
        this.shortlinkService = shortlinkService;
    }

    @PostMapping
    public ApiResult<ShortlinkView> create(@Valid @RequestBody CreateShortlinkRequest request) {
        Shortlink link = shortlinkService.create(request.url(), request.expireAt());
        return ApiResult.ok(ShortlinkView.from(link, shortlinkService.shortUrl(link.code())));
    }

    // 短码统一约束成 7 位 Base62：既避免与 /stats、/capacity 这些字面量路径打歧义，
    // 也让非法短码在路由层就被挡掉，不进入业务逻辑。
    @GetMapping("/{code:[0-9A-Za-z]{7}}")
    public ApiResult<ShortlinkView> get(@PathVariable("code") String code) {
        Shortlink link = shortlinkService.resolve(code)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "短码不存在或已过期: " + code));
        return ApiResult.ok(ShortlinkView.from(link, shortlinkService.shortUrl(link.code())));
    }

    @DeleteMapping("/{code:[0-9A-Za-z]{7}}")
    public ApiResult<Void> delete(@PathVariable("code") String code) {
        if (!shortlinkService.delete(code)) {
            throw new BizException(ErrorCode.NOT_FOUND, "短码不存在: " + code);
        }
        return ApiResult.ok(null);
    }

    @GetMapping("/{code:[0-9A-Za-z]{7}}/clicks")
    public ApiResult<Long> clicks(@PathVariable("code") String code) {
        return ApiResult.ok(shortlinkService.clicks(code));
    }

    /**
     * 手动刷一次点击增量。线上靠定时任务，这里留给实验观察"刷之前/刷之后"的差异。
     */
    @PostMapping("/clicks/flush")
    public ApiResult<Void> flushClicks() {
        shortlinkService.flushClicks();
        return ApiResult.ok(null);
    }

    @GetMapping("/stats")
    public ApiResult<ShortlinkStatsView> stats() {
        return ApiResult.ok(ShortlinkStatsView.from(shortlinkService.stats()));
    }

    /**
     * 容量模型：给出目标 QPS 下需要多少节点、各层实际 QPS 与利用率。
     *
     * <p>默认参数就是 100 万 QPS 场景，可直接看到"L1 命中率"对 Redis 的压力有多敏感。
     */
    @GetMapping("/capacity")
    public ApiResult<CapacityModel.Plan> capacity(
            @RequestParam(name = "targetQps", defaultValue = "1000000") long targetQps,
            @RequestParam(name = "l1HitRatio", defaultValue = "0.95") double l1HitRatio,
            @RequestParam(name = "l2HitRatioOfL1Miss", defaultValue = "0.9") double l2HitRatioOfL1Miss,
            @RequestParam(name = "singleNodeQps", defaultValue = "150000") long singleNodeQps,
            @RequestParam(name = "redisQpsCapacity", defaultValue = "500000") long redisQpsCapacity,
            @RequestParam(name = "dbQpsCapacity", defaultValue = "100000") long dbQpsCapacity) {
        return ApiResult.ok(CapacityModel.plan(new CapacityModel.Workload(
                targetQps, l1HitRatio, l2HitRatioOfL1Miss, singleNodeQps, redisQpsCapacity, dbQpsCapacity)));
    }
}
