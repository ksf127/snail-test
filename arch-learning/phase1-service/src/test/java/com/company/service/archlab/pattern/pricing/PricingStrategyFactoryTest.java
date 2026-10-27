package com.company.service.archlab.pattern.pricing;

import java.math.BigDecimal;
import java.util.List;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingStrategyFactoryTest {

    private final PricingStrategyFactory factory = new PricingStrategyFactory(List.of(
            new NormalPricingStrategy(),
            new VipPricingStrategy(),
            new PromotionPricingStrategy()));

    private final PricingService pricingService = new PricingService(factory);

    @Test
    void shouldCalculateEachStrategy() {
        PricingRequest request = new PricingRequest(2, new BigDecimal("100.00"));

        assertThat(pricingService.quote("NORMAL", request).payable()).isEqualByComparingTo("200.00");
        assertThat(pricingService.quote("VIP", request).payable()).isEqualByComparingTo("190.00");
        assertThat(pricingService.quote("PROMOTION", request).payable()).isEqualByComparingTo("200.00");
    }

    @Test
    void promotionShouldReduceFiftyWhenReachingThreeHundred() {
        PricingRequest request = new PricingRequest(4, new BigDecimal("100.00"));

        PricingService.Quote quote = pricingService.quote("PROMOTION", request);

        assertThat(quote.subtotal()).isEqualByComparingTo("400.00");
        assertThat(quote.payable()).isEqualByComparingTo("350.00");
    }

    @Test
    void shouldIgnoreCaseOfStrategyCode() {
        assertThat(pricingService.quote("vip", new PricingRequest(1, new BigDecimal("10.00"))).strategy())
                .isEqualTo("VIP");
    }

    @Test
    void shouldRejectUnknownStrategy() {
        assertThatThrownBy(() -> pricingService.quote("UNKNOWN", new PricingRequest(1, BigDecimal.ONE)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void shouldExposeRegisteredCodes() {
        assertThat(factory.codes()).containsExactlyInAnyOrder("NORMAL", "VIP", "PROMOTION");
    }

    @Test
    void shouldFailFastOnDuplicateCode() {
        PricingStrategy duplicatedNormal = new PricingStrategy() {
            @Override
            public String code() {
                return "normal";
            }

            @Override
            public BigDecimal calculate(PricingRequest request) {
                return BigDecimal.ZERO;
            }
        };

        assertThatThrownBy(() -> new PricingStrategyFactory(List.of(
                new NormalPricingStrategy(), duplicatedNormal)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复");
    }
}
