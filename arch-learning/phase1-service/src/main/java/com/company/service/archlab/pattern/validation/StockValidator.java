package com.company.service.archlab.pattern.validation;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 库存校验：这里用约定代替真实库存服务，保证实验可单机运行。
 *
 * <p>真实场景里这一步是 RPC/缓存调用，最耗时也最容易被下游拖垮，
 * 所以它在链路上的位置很关键：先做零成本的本地校验，再去打下游。
 *
 * <p>【解决什么问题】库存是下单流程里唯一"必须问外部系统"的校验，
 * 也是整条链路上最慢、最容易失败的一环。
 *
 * <p>【为什么这样设计】排在最后（{@code @Order(30)}）：
 * 前面的用户/数量校验都是纯内存判断，先把无效请求过滤掉，才不会让下游白挨流量。
 * 这就是责任链顺序的业务含义——顺序不是随便定的，它直接决定了下游 QPS。
 * 不用这个模式会怎样：库存调用会和其它 if 混在一个方法里，
 * 想调整"先本地后远程"的顺序就得动逻辑，风险和回归面都更大。
 *
 * <p>【怎么读这段代码】两个判断：skuId 空 → 40001，soldout 前缀 → 409（冲突类错误，
 * 与"参数错"区分开，调用方可以据此提示"库存不足"而不是"请求非法"）。
 */
@Component
@Order(30)
public class StockValidator implements OrderValidator {

    private static final Logger log = LoggerFactory.getLogger(StockValidator.class);

    /**
     * 校验商品可售。
     *
     * @param context 下单上下文，读取 skuId
     * @throws BizException skuId 为空（40001）或商品无库存（CONFLICT）时抛出
     */
    @Override
    public void validate(OrderContext context) {
        if (context.skuId() == null || context.skuId().isBlank()) {
            log.warn("【库存校验】skuId 为空，拒绝下单");
            throw new BizException(ErrorCode.PARAM_INVALID, "skuId 不能为空");
        }
        // 真实实现这里会是一次 RPC：日志刻意打在调用之后能看到结果的位置，
        // 避免"调用超时但没有任何日志"的排障黑洞
        if (context.skuId().startsWith("soldout")) {
            log.warn("【库存校验】商品无库存，skuId={}，userId={}", context.skuId(), context.userId());
            throw new BizException(ErrorCode.CONFLICT, "商品库存不足: " + context.skuId());
        }
        log.debug("【库存校验】通过，skuId={}", context.skuId());
    }
}
