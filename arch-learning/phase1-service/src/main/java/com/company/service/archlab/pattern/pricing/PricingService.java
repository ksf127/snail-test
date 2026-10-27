package com.company.service.archlab.pattern.pricing;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

/**
 * 计价门面：对外只暴露"给我策略 + 数量 + 单价，还我一个报价"。
 */
@Service
public class PricingService {

    public record Quote(String strategy, BigDecimal subtotal, BigDecimal payable) {
    }

    private final PricingStrategyFactory factory;

    public PricingService(PricingStrategyFactory factory) {
        this.factory = factory;
    }

    public Quote quote(String strategyCode, PricingRequest request) {
        PricingStrategy strategy = factory.of(strategyCode);
        BigDecimal payable = strategy.calculate(request);
        return new Quote(strategy.code(), request.subtotal(), payable);
    }
}
