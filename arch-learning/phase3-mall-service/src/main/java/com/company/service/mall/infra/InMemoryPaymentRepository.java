package com.company.service.mall.infra;

import com.company.service.mall.domain.Payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 支付流水的内存实现，同时提供 paymentNo 与 orderNo 两个查询维度。
 *
 * <p>【解决什么问题】支付回调可能重复到达，必须能按订单号快速判断"这单是不是已经付过"，
 * 又要能按支付流水号对外提供查询。一张表两个唯一键，就建两个索引。
 *
 * <p>【为什么这样设计】{@code byOrderNo} 用 {@code putIfAbsent}，保证"一单一付"：
 * 即使两个线程同时生成流水，也只有一个能成为该订单的有效流水，另一个留在
 * {@code byPaymentNo} 里可查（真实场景里应由唯一索引直接拒绝）。
 *
 * <p>【怎么读这段代码】{@link #save} 同时写两个索引；查询命中哪个索引就返回对应对象。
 * 日志一律 debug —— 内存实现每个操作都打 info 会刷屏。
 */
@Repository
public class InMemoryPaymentRepository implements PaymentRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPaymentRepository.class);

    private final ConcurrentMap<String, Payment> byPaymentNo = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Payment> byOrderNo = new ConcurrentHashMap<>();

    /**
     * 保存支付流水。幂等：同一 paymentNo 覆盖；同一订单只保留第一条流水。
     */
    @Override
    public void save(Payment payment) {
        byPaymentNo.put(payment.paymentNo(), payment);
        // 一单一付：订单维度只保留第一条，模拟订单号上的唯一索引
        Payment previous = byOrderNo.putIfAbsent(payment.orderNo(), payment);
        if (previous != null && !previous.paymentNo().equals(payment.paymentNo())) {
            log.debug("订单已有支付流水，保留原值: orderNo={}, 已存在={}, 本次={}",
                    payment.orderNo(), previous.paymentNo(), payment.paymentNo());
        }
        log.debug("保存支付流水: paymentNo={}, orderNo={}", payment.paymentNo(), payment.orderNo());
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
