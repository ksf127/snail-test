package com.company.service.mall.domain;

import java.time.Instant;

/**
 * 支付流水。一张订单只对应一条支付流水（重复支付在 OrderService 中按订单号串行化）。
 *
 * <p>【解决什么问题】把"这笔订单确实收过钱"落成一条独立记录。
 * 订单状态只说明"已支付"，而支付流水还带支付单号、金额、支付时间，
 * 对账、退款、通知商户都要靠它，不能只依赖订单表上的一个状态位。
 *
 * <p>【为什么这样设计】支付流水是不可变记录（record）：一旦生成就不允许修改，
 * 需要变更只能新增冲正/退款记录。这样"支付事实"永远可追溯。
 * {@code paymentNo} 由订单号派生（{@code P + orderNo}），天然保证一单一付，
 * 也让上游重复回调时能直接查到已有流水。
 *
 * <p>【怎么读这段代码】由 {@code OrderService.pay} 创建并立刻落库；
 * 同一订单再次发起支付时，仓储会按 orderNo 找回它，而不是新建第二条。
 *
 * @param paymentNo   支付流水号，不能为空
 * @param orderNo     关联订单号，不能为空
 * @param userId      付款用户
 * @param amountCents 支付金额（分），不能为负
 * @param paidAt      支付时间
 */
public record Payment(String paymentNo, String orderNo, long userId, long amountCents, Instant paidAt) {

    /**
     * 紧凑构造器：保证流水号/订单号非空、金额非负。
     *
     * @throws IllegalArgumentException 关键字段非法
     */
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
