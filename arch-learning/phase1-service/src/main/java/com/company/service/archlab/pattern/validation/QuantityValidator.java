package com.company.service.archlab.pattern.validation;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 数量校验：单次下单 1~100 件。
 * 防刷的第一道闸门就在这里，放到结算页再拦已经晚了。
 *
 * <p>【解决什么问题】数量既要防"0 / 负数"这种脏参数，也要防"一次买十万件"这种对库存和风控的冲击。
 * 这类限制必须在最便宜的环节拦住，否则请求会一路打到库存和支付。
 *
 * <p>【为什么这样设计】上限抽成常量 {@code MAX_QUANTITY}，且错误信息里同时回显"允许范围 + 当前值"：
 * 排障时不需要再去翻代码确认阈值。放在第二顺位（{@code @Order(20)}）：
 * 用户校验之后、任何要打下游的校验之前，保证无效请求不会产生 RPC 开销。
 * 不用这个模式会怎样：这个上限会以魔法数字 100 的形式散落在多个入口，
 * 运营调整限购时必然漏改。
 *
 * <p>【怎么读这段代码】只有一个 if，注意它是"或"关系：
 * 下界与上界任一不满足都拒绝，避免负数通过。
 */
@Component
@Order(20)
public class QuantityValidator implements OrderValidator {

    private static final Logger log = LoggerFactory.getLogger(QuantityValidator.class);

    private static final int MAX_QUANTITY = 100;

    /**
     * 校验购买数量。
     *
     * @param context 下单上下文，读取 quantity
     * @throws BizException 数量不在 [1, 100] 时抛出（PARAM_INVALID）
     */
    @Override
    public void validate(OrderContext context) {
        if (context.quantity() <= 0 || context.quantity() > MAX_QUANTITY) {
            // 日志带上限与当前值：这样"是不是恶意刷单"一眼能看出来
            log.warn("【数量校验】数量越界，quantity={}，允许区间=[1, {}]",
                    context.quantity(), MAX_QUANTITY);
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "购买数量必须在 [1, " + MAX_QUANTITY + "] 之间，当前: " + context.quantity());
        }
        log.debug("【数量校验】通过，quantity={}", context.quantity());
    }
}
