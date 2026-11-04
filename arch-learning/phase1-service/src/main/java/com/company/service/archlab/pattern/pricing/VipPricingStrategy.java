package com.company.service.archlab.pattern.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * VIP：全单 95 折。
 *
 * <p>【解决什么问题】会员折扣是一条独立规则，而且未来可能改成 9 折、按等级分档、或者只对部分品类生效。
 * 把它隔离成一个类后，折扣率变化的影响面被限制在这一个文件里。
 *
 * <p>【为什么这样设计】折扣率用 {@code static final BigDecimal} 而不是 {@code 0.95} 字面量：
 * 既避免 double 的精度问题，也让"唯一事实来源"只剩一处。
 * 不用这个模式会怎样：折扣率会散落在 Legacy 的 if 分支里，运营改一次价，开发和测试都要全量回归。
 *
 * <p>【怎么读这段代码】只需要看 {@link #calculate}：小计 × 折扣率 → 两位小数。
 * 想验证效果：{@code GET /patterns/pricing?strategy=VIP&quantity=2&unitPrice=100}
 * 应付应为 190.00，和 {@code /patterns/pricing/legacy} 的返回一致。
 */
@Component
public class VipPricingStrategy implements PricingStrategy {

    private static final Logger log = LoggerFactory.getLogger(VipPricingStrategy.class);

    private static final BigDecimal DISCOUNT = new BigDecimal("0.95");

    @Override
    public String code() {
        return "VIP";
    }

    /**
     * VIP 折扣计价。
     *
     * @param request 计价入参，非空
     * @return 小计 × 0.95，保留两位小数、四舍五入
     */
    @Override
    public BigDecimal calculate(PricingRequest request) {
        // 先乘再舍入：如果先舍入小计再打折，会产生分位误差，对账时会对不上
        BigDecimal payable = request.subtotal().multiply(DISCOUNT).setScale(2, RoundingMode.HALF_UP);
        log.debug("【VIP 计价】小计={}，折扣={}，应付={}", request.subtotal(), DISCOUNT, payable);
        return payable;
    }
}
