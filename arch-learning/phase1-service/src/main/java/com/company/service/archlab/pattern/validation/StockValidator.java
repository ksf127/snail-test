package com.company.service.archlab.pattern.validation;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 库存校验：这里用约定代替真实库存服务，保证实验可单机运行。
 *
 * <p>真实场景里这一步是 RPC/缓存调用，最耗时也最容易被下游拖垮，
 * 所以它在链路上的位置很关键：先做零成本的本地校验，再去打下游。
 */
@Component
@Order(30)
public class StockValidator implements OrderValidator {

    @Override
    public void validate(OrderContext context) {
        if (context.skuId() == null || context.skuId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "skuId 不能为空");
        }
        if (context.skuId().startsWith("soldout")) {
            throw new BizException(ErrorCode.CONFLICT, "商品库存不足: " + context.skuId());
        }
    }
}
