package com.company.service.archlab.pattern.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/**
 * 原价：无优惠。
 */
@Component
public class NormalPricingStrategy implements PricingStrategy {

    @Override
    public String code() {
        return "NORMAL";
    }

    @Override
    public BigDecimal calculate(PricingRequest request) {
        return request.subtotal().setScale(2, RoundingMode.HALF_UP);
    }
}
