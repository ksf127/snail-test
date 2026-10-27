package com.company.service.archlab.pattern.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/**
 * VIP：全单 95 折。
 */
@Component
public class VipPricingStrategy implements PricingStrategy {

    private static final BigDecimal DISCOUNT = new BigDecimal("0.95");

    @Override
    public String code() {
        return "VIP";
    }

    @Override
    public BigDecimal calculate(PricingRequest request) {
        return request.subtotal().multiply(DISCOUNT).setScale(2, RoundingMode.HALF_UP);
    }
}
