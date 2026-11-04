package com.company.service.mall.infra;

import com.company.service.mall.domain.Payment;

import java.util.Optional;

/**
 * 支付流水仓储：按流水号与订单号双维度查询。
 *
 * <p>【解决什么问题】支付回调必须幂等：同一订单重复回调时，要能立刻找到已有流水并直接返回，
 * 而不是再建一条新流水、再扣一次库存。
 *
 * <p>【为什么这样设计】两个唯一键对应两个真实查询入口：对外按 paymentNo 查，
 * 对内按 orderNo 判断"这单是否已支付"。抽成接口是为了让内存实现与落库实现
 * 共享同一份契约；落库时 orderNo 上必须有唯一索引，否则并发支付仍会写出两条。
 *
 * <p>【怎么读这段代码】{@link #save} 是 upsert；两个 find 都是只读、可重复调用。
 */
public interface PaymentRepository {

    /**
     * 保存支付流水。幂等：同一 paymentNo 覆盖。
     */
    void save(Payment payment);

    Optional<Payment> findByPaymentNo(String paymentNo);

    /**
     * 一单一付：按订单号查询既能做重放幂等，也能在并发支付时发现已完成的流水。
     *
     * <p>幂等：只读，可重复调用。
     */
    Optional<Payment> findByOrderNo(String orderNo);

    long count();
}
