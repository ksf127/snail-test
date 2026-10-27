package com.company.service.archlab.pattern.validation;

import java.math.BigDecimal;

/**
 * 下单校验上下文：责任链上每个节点只读自己关心的字段。
 */
public record OrderContext(String userId,
                           String skuId,
                           int quantity,
                           BigDecimal unitPrice,
                           String couponCode) {
}
