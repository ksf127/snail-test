package com.company.service.archlab.scenario.distributedtx;

import java.util.List;
import java.util.function.Supplier;

import com.company.common.core.ApiResult;
import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分布式事务对照入口：AT / TCC / Saga。
 *
 * <p>【解决什么问题】把"同一笔跨服务下单，用三种不同事务机制分别跑一遍"做成 HTTP 接口，
 * 让 AT/TCC/Saga 的差异可以用同一条 URL 复现，而不是靠读文档想象。
 *
 * <p>【为什么这样设计】三种机制在同一个场景（建订单 → 扣库存 → 扣余额）下实现完全不同：
 * <ul>
 *   <li><b>AT</b>：一阶段直接写库并记录 undo log，靠全局行锁挡住并发；
 *       失败时按 undo log 逆序还原。业务无侵入，代价是全程持锁、性能最差。</li>
 *   <li><b>TCC</b>：Try 预占、Confirm 扣减、Cancel 归还，三个方法都得业务自己写并且幂等；
 *       失败时只 Cancel 已 Try 成功的分支。隔离性靠"冻结份额"实现，代价是改造量大。</li>
 *   <li><b>Saga</b>：正向步骤逐个提交，失败后反向执行补偿；没有锁也没有隔离，
 *       中间态对读方可见。代价是每步都要写补偿，且补偿必须幂等、可能失败。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】{@code /distributed-tx?mechanism=XXX&failAt=N} 只跑一种机制，
 * {@code /distributed-tx/compare?failAt=N} 跑三种并返回同一结构的列表，直接对照即可：
 * 成功时三者终态一致；失败时都回到初始值，差别在 isolation 文案、
 * 回滚动作次数（undoLogsApplied / compensations）和 lockConflicts。
 *
 * <p>建议对照：
 * <ul>
 *   <li>{@code /distributed-tx?mechanism=AT&failAt=2}：AT 走 undo log 回滚</li>
 *   <li>{@code /distributed-tx?mechanism=TCC&failAt=2}：TCC 走 Cancel 归还冻结份额</li>
 *   <li>{@code /distributed-tx?mechanism=SAGA&failAt=2}：Saga 反向补偿</li>
 *   <li>{@code /distributed-tx/compare?failAt=-1}：同一场景三种机制横向对比</li>
 * </ul>
 */
@RestController
@RequestMapping("/distributed-tx")
public class DistributedTxController {

    private static final Logger log = LoggerFactory.getLogger(DistributedTxController.class);

    private final DistributedTxLab distributedTxLab;

    public DistributedTxController(DistributedTxLab distributedTxLab) {
        this.distributedTxLab = distributedTxLab;
    }

    /**
     * 跑一种机制，例如：{@code ?mechanism=AT&failAt=2}（failAt=-1 表示全部成功）。
     *
     * @param mechanism 事务机制，缺省 AT
     * @param failAt    第几个分支失败（0 基），-1 表示全部成功
     * @return 该机制的终态与回滚统计
     * @throws BizException failAt 越界等参数错误（转换为 PARAM_INVALID）
     */
    @GetMapping
    public ApiResult<DistributedTxLab.Result> run(
            @RequestParam(name = "mechanism", defaultValue = "AT") DistributedTxLab.Mechanism mechanism,
            @RequestParam(name = "failAt", defaultValue = "-1") int failAt) {
        log.info("【分布式事务实验】收到请求: mechanism={}, failAt={}", mechanism, failAt);
        return execute(() -> distributedTxLab.run(mechanism, failAt));
    }

    /**
     * 三种机制跑同一场景，例如：{@code ?failAt=1}。
     *
     * @param failAt 第几个分支失败（0 基），-1 表示全部成功
     * @return 按 AT/TCC/Saga 顺序排列的三条结果
     * @throws BizException failAt 越界等参数错误
     */
    @GetMapping("/compare")
    public ApiResult<List<DistributedTxLab.Result>> compare(
            @RequestParam(name = "failAt", defaultValue = "-1") int failAt) {
        log.info("【分布式事务实验】收到横向对比请求: failAt={}", failAt);
        return execute(() -> distributedTxLab.compare(failAt));
    }

    /**
     * 统一异常转换：实验参数错误返回 400 语义，其他异常交给全局异常处理器。
     */
    private <T> ApiResult<T> execute(Supplier<T> experiment) {
        try {
            return ApiResult.ok(experiment.get());
        } catch (IllegalArgumentException e) {
            log.warn("【分布式事务实验】参数非法: {}", e.getMessage());
            throw new BizException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
    }
}
