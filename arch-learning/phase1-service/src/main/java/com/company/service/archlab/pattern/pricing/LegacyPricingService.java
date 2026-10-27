package com.company.service.archlab.pattern.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.springframework.stereotype.Service;

/**
 * 反面教材：同一个需求用 if/else 堆出来的样子。保留它不是为了用，是为了对照。
 *
 * <p>它的具体问题（也是代码评审里最该指出的）：
 * <ol>
 *   <li>加一个"新客首单立减"就要改这个方法，所有策略的回归测试都得重跑；</li>
 *   <li>策略越多，方法越长，圈复杂度直接爆表，Code Review 只能看到 diff 看不到全貌；</li>
 *   <li>金额边界（空值、负数、精度）只能在每个分支里各写一遍，迟早漏；</li>
 *   <li>无法按渠道/租户动态装配策略，也无法单独做单测覆盖。</li>
 * </ol>
 */
@Deprecated(since = "arch-lab", forRemoval = false)
@Service
public class LegacyPricingService {

    @SuppressWarnings("java:S3776")
    public BigDecimal price(String code, PricingRequest request) {
        BigDecimal subtotal = request.subtotal();
        if (code == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "strategy 不能为空");
        }
        if ("NORMAL".equalsIgnoreCase(code)) {
            return subtotal.setScale(2, RoundingMode.HALF_UP);
        } else if ("VIP".equalsIgnoreCase(code)) {
            return subtotal.multiply(new BigDecimal("0.95")).setScale(2, RoundingMode.HALF_UP);
        } else if ("PROMOTION".equalsIgnoreCase(code)) {
            if (subtotal.compareTo(new BigDecimal("300")) >= 0) {
                return subtotal.subtract(new BigDecimal("50")).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            }
            return subtotal.setScale(2, RoundingMode.HALF_UP);
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "未知计价策略: " + code);
    }
}
