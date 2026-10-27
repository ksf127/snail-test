package com.company.service.archlab.pattern.validation;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 数量校验：单次下单 1~100 件。
 * 防刷的第一道闸门就在这里，放到结算页再拦已经晚了。
 */
@Component
@Order(20)
public class QuantityValidator implements OrderValidator {

    private static final int MAX_QUANTITY = 100;

    @Override
    public void validate(OrderContext context) {
        if (context.quantity() <= 0 || context.quantity() > MAX_QUANTITY) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "购买数量必须在 [1, " + MAX_QUANTITY + "] 之间，当前: " + context.quantity());
        }
    }
}
