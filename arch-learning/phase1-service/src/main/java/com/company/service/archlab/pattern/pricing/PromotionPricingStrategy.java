package com.company.service.archlab.pattern.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 促销：满 300 减 50，且不低于 0。
 *
 * <p>【解决什么问题】门槛型优惠比纯折扣多一个"条件判断 + 金额兜底"的步骤，
 * 逻辑更复杂，也更容易在复制粘贴到其它分支时出错。独立成类后它可以被单独写测试。
 *
 * <p>【为什么这样设计】门槛和减免额都抽成常量，避免魔法值散落；
 * 用 {@code compareTo} 而不是 {@code >=}：BigDecimal 的 equals 连精度一起比，
 * 用 equals/== 判断金额大小是经典事故。
 * 不用这个模式会怎样：这段"门槛 + 减额 + 归零 + 舍入"会直接嵌在 Legacy 的 else-if 里，
 * 想复用或单独测都做不到。
 *
 * <p>【怎么读这段代码】三步：算小计 → 判断是否过门槛 → 减完后不允许为负。
 * 验证：{@code ?strategy=PROMOTION&quantity=3&unitPrice=100} 小计 300 应付 250.00；
 * 数量改成 2 则应付 200.00（未达门槛）。
 */
@Component
public class PromotionPricingStrategy implements PricingStrategy {

    private static final Logger log = LoggerFactory.getLogger(PromotionPricingStrategy.class);

    private static final BigDecimal THRESHOLD = new BigDecimal("300");

    private static final BigDecimal REDUCTION = new BigDecimal("50");

    @Override
    public String code() {
        return "PROMOTION";
    }

    /**
     * 满减计价。
     *
     * @param request 计价入参，非空
     * @return 达标则减去 50，否则原价；结果不小于 0，保留两位小数
     */
    @Override
    public BigDecimal calculate(PricingRequest request) {
        BigDecimal subtotal = request.subtotal();
        // 用 compareTo 而不是 equals：equals 会比较 scale（300 与 300.00 不相等），金额比较必须只比数值
        BigDecimal payable = subtotal.compareTo(THRESHOLD) >= 0 ? subtotal.subtract(REDUCTION) : subtotal;
        // max(ZERO) 兜底：满减规则以后若改成"减 500"，负金额会直接导致支付失败
        BigDecimal result = payable.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        log.debug("【PROMOTION 计价】小计={}，是否达门槛={}，应付={}",
                subtotal, subtotal.compareTo(THRESHOLD) >= 0, result);
        return result;
    }
}
