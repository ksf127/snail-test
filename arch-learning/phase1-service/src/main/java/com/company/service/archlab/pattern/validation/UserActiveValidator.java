package com.company.service.archlab.pattern.validation;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 用户状态校验：非空且未被封禁。
 * {@code @Order} 决定链路顺序，数值越小越先执行。
 */
@Component
@Order(10)
public class UserActiveValidator implements OrderValidator {

    @Override
    public void validate(OrderContext context) {
        if (context.userId() == null || context.userId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "userId 不能为空");
        }
        if (context.userId().startsWith("blocked")) {
            throw new BizException(ErrorCode.FORBIDDEN, "用户已被封禁: " + context.userId());
        }
    }
}
