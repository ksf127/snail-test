package com.company.service.mall.domain;

import java.time.Instant;

/**
 * 支付流水。一张订单只对应一条支付流水（重复支付在 OrderService 中按订单号串行化）。
 */
public record Payment(String paymentNo, String orderNo, long userId, long amountCents, Instant paidAt) {

    public Payment {
        if (paymentNo == null || paymentNo.isBlank()) {
            throw new IllegalArgumentException("paymentNo 不能为空");
        }
        if (orderNo == null || orderNo.isBlank()) {
            throw new IllegalArgumentException("orderNo 不能为空");
        }
        if (amountCents < 0) {
            throw new IllegalArgumentException("支付金额不能为负: " + amountCents);
        }
    }
}
