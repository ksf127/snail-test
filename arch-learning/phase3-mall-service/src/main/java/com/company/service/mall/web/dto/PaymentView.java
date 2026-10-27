package com.company.service.mall.web.dto;

import com.company.service.mall.domain.Payment;

import java.time.Instant;

public record PaymentView(String paymentNo,
                          String orderNo,
                          long userId,
                          long amountCents,
                          Instant paidAt) {

    public static PaymentView from(Payment payment) {
        return new PaymentView(payment.paymentNo(), payment.orderNo(), payment.userId(),
                payment.amountCents(), payment.paidAt());
    }
}
