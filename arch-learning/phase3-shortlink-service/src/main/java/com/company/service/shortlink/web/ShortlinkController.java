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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>【解决什么问题】短链系统的"控制面"：创建和删除是低频写，统计与容量模型是观察窗口。
 * 它和跳转入口分开，是因为两者的流量差了几个数量级 —— 管理接口可以做完整校验和 info 日志，
 * 跳转接口则必须克制到只剩 debug。
 *
 * <p>【为什么这样设计】
 * <ul>
 *   <li>短码统一约束成 7 位 Base62 正则：避免与 {@code /stats}、{@code /capacity}
 *       这些字面量路径打歧义，也让非法短码在路由层就被挡掉；</li>
 *   <li>写接口返回 {@link ShortlinkView} 而不是实体：数字 id 属于内部发号细节；</li>
 *   <li>容量模型接口带默认参数（100 万 QPS），打开就能看到 L1 命中率对 Redis 压力的敏感度。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】从上到下是"写 → 读 → 删 → 点击量 → 刷盘 → 统计 → 容量"，
 * 每个方法只做参数转发与 DTO 转换，业务判断都在 {@link ShortlinkService}。
 * 本类无可变状态，线程安全。
 */
@RestController
@RequestMapping("/links")
public class ShortlinkController {

    private static final Logger log = LoggerFactory.getLogger(ShortlinkController.class);

    private final ShortlinkService shortlinkService;

    public ShortlinkController(ShortlinkService shortlinkService) {
        this.shortlinkService = shortlinkService;
    }

    /**
     * 创建短链。
     *
     * <p>创建是低频写操作，入口用 info 记录原始 URL，便于审计与排查。
     *
     * @param request 创建入参，url 与 expireAt 由 Bean Validation 与 Service 双重校验
     * @return 短链视图（含完整短链 URL）
     * @throws BizException URL 非法或过期时间不在未来时
     */
    @PostMapping
    public ApiResult<ShortlinkView> create(@Valid @RequestBody CreateShortlinkRequest request) {
        log.info("创建短链请求: url={}, expireAt={}", request.url(), request.expireAt());
        Shortlink link = shortlinkService.create(request.url(), request.expireAt());
        return ApiResult.ok(ShortlinkView.from(link, shortlinkService.shortUrl(link.code())));
    }

    // 短码统一约束成 7 位 Base62：既避免与 /stats、/capacity 这些字面量路径打歧义，
    // 也让非法短码在路由层就被挡掉，不进入业务逻辑。
    /**
     * 按短码查询。
     *
     * <p>走的是和跳转完全相同的解析链路（布隆 → L1 → L2 → 单飞 → 存储），
     * 因此这里的结果可以直接用来验证缓存分层是否生效。
     *
     * @param code 7 位短码
     * @return 短链视图
     * @throws BizException 短码不存在或已过期时
     */
    @GetMapping("/{code:[0-9A-Za-z]{7}}")
    public ApiResult<ShortlinkView> get(@PathVariable("code") String code) {
        log.debug("查询短链请求: code={}", code);
        Shortlink link = shortlinkService.resolve(code)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "短码不存在或已过期: " + code));
        return ApiResult.ok(ShortlinkView.from(link, shortlinkService.shortUrl(link.code())));
    }

    /**
     * 删除短链。
     *
     * @param code 7 位短码
     * @return 空结果
     * @throws BizException 短码不存在时
     */
    @DeleteMapping("/{code:[0-9A-Za-z]{7}}")
    public ApiResult<Void> delete(@PathVariable("code") String code) {
        log.info("删除短链请求: code={}", code);
        if (!shortlinkService.delete(code)) {
            throw new BizException(ErrorCode.NOT_FOUND, "短码不存在: " + code);
        }
        return ApiResult.ok(null);
    }

    /**
     * 查询点击量（已落盘 + 尚未刷新的增量）。
     *
     * @param code 7 位短码
     * @return 点击次数
     */
    @GetMapping("/{code:[0-9A-Za-z]{7}}/clicks")
    public ApiResult<Long> clicks(@PathVariable("code") String code) {
        log.debug("查询点击量请求: code={}", code);
        return ApiResult.ok(shortlinkService.clicks(code));
    }

    /**
     * 手动刷一次点击增量。线上靠定时任务，这里留给实验观察"刷之前/刷之后"的差异。
     *
     * <p>触发一次批量落盘，属于低频关键动作，用 info 留痕。
     */
    @PostMapping("/clicks/flush")
    public ApiResult<Void> flushClicks() {
        log.info("手动触发点击增量刷盘");
        shortlinkService.flushClicks();
        return ApiResult.ok(null);
    }

    /**
     * 运行计数：各层命中、回源、布隆拦截、单飞降级。
     *
     * @return 计数快照与派生命中率
     */
    @GetMapping("/stats")
    public ApiResult<ShortlinkStatsView> stats() {
        log.debug("查询运行计数");
        return ApiResult.ok(ShortlinkStatsView.from(shortlinkService.stats()));
    }

    /**
     * 容量模型：给出目标 QPS 下需要多少节点、各层实际 QPS 与利用率。
     *
     * <p>默认参数就是 100 万 QPS 场景，可直接看到"L1 命中率"对 Redis 的压力有多敏感。
     *
     * @param targetQps           目标总 QPS
     * @param l1HitRatio          L1 命中率（分母是全部请求）
     * @param l2HitRatioOfL1Miss  L2 命中率（分母是 L1 未命中）
     * @param singleNodeQps       单机承载 QPS
     * @param redisQpsCapacity    Redis 集群容量
     * @param dbQpsCapacity       存储容量
     * @return 节点数、流量拆分、利用率与告警
     * @throws IllegalArgumentException 参数越界时（由 CapacityModel 校验）
     */
    @GetMapping("/capacity")
    public ApiResult<CapacityModel.Plan> capacity(
            @RequestParam(name = "targetQps", defaultValue = "1000000") long targetQps,
            @RequestParam(name = "l1HitRatio", defaultValue = "0.95") double l1HitRatio,
            @RequestParam(name = "l2HitRatioOfL1Miss", defaultValue = "0.9") double l2HitRatioOfL1Miss,
            @RequestParam(name = "singleNodeQps", defaultValue = "150000") long singleNodeQps,
            @RequestParam(name = "redisQpsCapacity", defaultValue = "500000") long redisQpsCapacity,
            @RequestParam(name = "dbQpsCapacity", defaultValue = "100000") long dbQpsCapacity) {
        log.debug("容量模型计算: targetQps={}, l1HitRatio={}, l2HitRatioOfL1Miss={}",
                targetQps, l1HitRatio, l2HitRatioOfL1Miss);
        return ApiResult.ok(CapacityModel.plan(new CapacityModel.Workload(
                targetQps, l1HitRatio, l2HitRatioOfL1Miss, singleNodeQps, redisQpsCapacity, dbQpsCapacity)));
    }
}
