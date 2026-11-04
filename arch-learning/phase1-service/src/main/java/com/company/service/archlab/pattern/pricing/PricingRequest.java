package com.company.service.archlab.pattern.pricing;

import java.math.BigDecimal;

/**
 * 计价入参：只放"算钱需要的东西"，不要塞整个订单实体。
 * 参数对象是策略模式能落地的前提——接口参数一旦膨胀，策略就会互相耦合。
 *
 * <p>【解决什么问题】计价方法如果直接收订单实体，每个策略都会顺手去读实体的其它字段，
 * 于是"算钱"和"订单模型"绑死，订单字段一改，所有策略都要改。这里把依赖收敛成两个值。
 *
 * <p>【为什么这样设计】用 record 而不是普通 POJO：入参应当不可变，
 * 否则策略内部随手 set 一下就会污染下游。校验放在紧凑构造器里，
 * 保证"只要拿到了 PricingRequest，就一定是一个合法的请求"——这叫把约束前移到类型入口，
 * 避免每个策略各写一遍空值/负数判断。
 *
 * <p>【怎么读这段代码】先看紧凑构造器里的两条校验，再看 {@link #subtotal()}：
 * 它是三个策略共同复用的"小计"，避免每处都写一遍 quantity * unitPrice。
 */
public record PricingRequest(int quantity, BigDecimal unitPrice) {

    /**
     * 紧凑构造器：所有校验在这里做一次，后续策略可以直接信任入参。
     *
     * @throws IllegalArgumentException 数量非正、单价为空或为负时抛出
     */
    public PricingRequest {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity 必须大于 0");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice 不能为负");
        }
    }

    /**
     * 小计金额（未打折、未减免）。
     *
     * @return quantity * unitPrice，使用 BigDecimal 以避免 double 的精度丢失
     */
    public BigDecimal subtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
