package com.company.service.archlab.pattern.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

/**
 * 促销：满 300 减 50，且不低于 0。
 */
@Component
public class PromotionPricingStrategy implements PricingStrategy {

    private static final BigDecimal THRESHOLD = new BigDecimal("300");

    private static final BigDecimal REDUCTION = new BigDecimal("50");

    @Override
    public String code() {
        return "PROMOTION";
    }

    @Override
    public BigDecimal calculate(PricingRequest request) {
        BigDecimal subtotal = request.subtotal();
        BigDecimal payable = subtotal.compareTo(THRESHOLD) >= 0 ? subtotal.subtract(REDUCTION) : subtotal;
        return payable.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }
}
