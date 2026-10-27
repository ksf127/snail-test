package com.company.service.archlab.pattern.pricing;

import java.math.BigDecimal;

/**
 * 计价入参：只放"算钱需要的东西"，不要塞整个订单实体。
 * 参数对象是策略模式能落地的前提——接口参数一旦膨胀，策略就会互相耦合。
 */
public record PricingRequest(int quantity, BigDecimal unitPrice) {

    public PricingRequest {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 必须大于 0");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice 不能为负");
        }
    }

    public BigDecimal subtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
