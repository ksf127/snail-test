package com.company.service.mall.infra;

import com.company.service.mall.domain.Payment;

import java.util.Optional;

public interface PaymentRepository {

    void save(Payment payment);

    Optional<Payment> findByPaymentNo(String paymentNo);

    /**
     * 一单一付：按订单号查询既能做重放幂等，也能在并发支付时发现已完成的流水。
     */
    Optional<Payment> findByOrderNo(String orderNo);

    long count();
}
