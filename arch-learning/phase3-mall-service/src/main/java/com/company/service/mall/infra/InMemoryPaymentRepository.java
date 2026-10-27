package com.company.service.mall.infra;

import com.company.service.mall.domain.Payment;

import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryPaymentRepository implements PaymentRepository {

    private final ConcurrentMap<String, Payment> byPaymentNo = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Payment> byOrderNo = new ConcurrentHashMap<>();

    @Override
    public void save(Payment payment) {
        byPaymentNo.put(payment.paymentNo(), payment);
        byOrderNo.putIfAbsent(payment.orderNo(), payment);
    }

    @Override
    public Optional<Payment> findByPaymentNo(String paymentNo) {
        return Optional.ofNullable(byPaymentNo.get(paymentNo));
    }

    @Override
    public Optional<Payment> findByOrderNo(String orderNo) {
        return Optional.ofNullable(byOrderNo.get(orderNo));
    }

    @Override
    public long count() {
        return byPaymentNo.size();
    }
}
