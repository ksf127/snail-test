package com.company.service.archlab.pattern.validation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidationChainTest {

    private final ValidationChain chain = buildChain();

    @Test
    void shouldRunAllNodesInOrderForLegalOrder() {
        List<String> executed = chain.validate(context("u-1", "sku-1", 2));

        assertThat(executed).containsExactly(
                "UserActiveValidator", "QuantityValidator", "StockValidator");
    }

    @Test
    void shouldRejectBlockedUserBeforeReachingLaterNodes() {
        assertThatThrownBy(() -> chain.validate(context("blocked-9", "sku-1", 2)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void shouldRejectQuantityOutOfRange() {
        assertThatThrownBy(() -> chain.validate(context("u-1", "sku-1", 0)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);

        assertThatThrownBy(() -> chain.validate(context("u-1", "sku-1", 101)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    void shouldRejectSoldOutSkuWithConflict() {
        assertThatThrownBy(() -> chain.validate(context("u-1", "soldout-1", 2)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void shouldSortNodesByOrderAnnotation() {
        List<OrderValidator> shuffled = new ArrayList<>(List.of(
                new StockValidator(), new UserActiveValidator(), new QuantityValidator()));

        AnnotationAwareOrderComparator.sort(shuffled);

        assertThat(shuffled).extracting(OrderValidator::name).containsExactly(
                "UserActiveValidator", "QuantityValidator", "StockValidator");
    }

    private static ValidationChain buildChain() {
        List<OrderValidator> validators = new ArrayList<>(List.of(
                new UserActiveValidator(), new QuantityValidator(), new StockValidator()));
        AnnotationAwareOrderComparator.sort(validators);
        return new ValidationChain(validators);
    }

    private static OrderContext context(String userId, String skuId, int quantity) {
        return new OrderContext(userId, skuId, quantity, new BigDecimal("99.00"), null);
    }
}
