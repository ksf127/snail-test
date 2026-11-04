package com.company.service.mall.web.dto;

import com.company.service.mall.domain.Payment;

import java.time.Instant;

/**
 * 支付流水视图。
 *
 * <p>【解决什么问题】把支付事实以只读结构返回给调用方：流水号、关联订单、金额、支付时间。
 * 支付是最敏感的数据，不应把领域对象直接序列化到响应里，避免内部字段意外外泄。
 *
 * <p>【为什么这样设计】字段与 {@link Payment} 一一对应但不含任何可变状态，
 * 调用方拿到后既不能改金额也不能改时间。金额用 long 分，前端展示时再格式化。
 *
 * <p>【怎么读这段代码】{@link #from} 是纯字段拷贝，无逻辑；
 * 由 {@code MallController.pay} 与订单查询接口调用。
 *
 * @param paymentNo   支付流水号
 * @param orderNo     关联订单号
 * @param userId      付款用户
 * @param amountCents 支付金额（分）
 * @param paidAt      支付时间
 */
public record PaymentView(String paymentNo,
                          String orderNo,
                          long userId,
                          long amountCents,
                          Instant paidAt) {

    /**
     * 支付流水转视图。
     *
     * <p>幂等：纯转换，可重复调用。
     *
     * @param payment 支付流水实体
     * @return 只读视图
     */
    public static PaymentView from(Payment payment) {
        return new PaymentView(payment.paymentNo(), payment.orderNo(), payment.userId(),
                payment.amountCents(), payment.paidAt());
    }
}
