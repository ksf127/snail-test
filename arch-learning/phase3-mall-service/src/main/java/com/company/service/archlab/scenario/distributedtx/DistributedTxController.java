package com.company.service.archlab.scenario.distributedtx;

import java.util.List;
import java.util.function.Supplier;

import com.company.common.core.ApiResult;
import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分布式事务对照入口：AT / TCC / Saga。
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

    private final DistributedTxLab distributedTxLab;

    public DistributedTxController(DistributedTxLab distributedTxLab) {
        this.distributedTxLab = distributedTxLab;
    }

    /**
     * 跑一种机制，例如：{@code ?mechanism=AT&failAt=2}（failAt=-1 表示全部成功）。
     */
    @GetMapping
    public ApiResult<DistributedTxLab.Result> run(
            @RequestParam(name = "mechanism", defaultValue = "AT") DistributedTxLab.Mechanism mechanism,
            @RequestParam(name = "failAt", defaultValue = "-1") int failAt) {
        return execute(() -> distributedTxLab.run(mechanism, failAt));
    }

    /**
     * 三种机制跑同一场景，例如：{@code ?failAt=1}。
     */
    @GetMapping("/compare")
    public ApiResult<List<DistributedTxLab.Result>> compare(
            @RequestParam(name = "failAt", defaultValue = "-1") int failAt) {
        return execute(() -> distributedTxLab.compare(failAt));
    }

    private <T> ApiResult<T> execute(Supplier<T> experiment) {
        try {
            return ApiResult.ok(experiment.get());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
    }
}
