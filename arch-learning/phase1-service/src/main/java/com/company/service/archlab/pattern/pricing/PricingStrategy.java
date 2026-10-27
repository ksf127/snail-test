package com.company.service.archlab.pattern.pricing;

import java.math.BigDecimal;

/**
 * 计价策略（策略模式）。
 *
 * <p>新增一种优惠 = 新增一个实现类 + 一个 {@code code()}，不碰任何已有代码，
 * 也不会和同事的改动产生合并冲突。这是"开闭原则"唯一真正可落地的形态。
 */
public interface PricingStrategy {

    /** 策略标识，必须全局唯一；由工厂做映射，接口里不要出现 if/switch。 */
    String code();

    /** 按策略计算应付金额。 */
    BigDecimal calculate(PricingRequest request);
}
