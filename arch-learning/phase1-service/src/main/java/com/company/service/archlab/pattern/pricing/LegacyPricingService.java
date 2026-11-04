package com.company.service.archlab.pattern.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 反面教材：同一个需求用 if/else 堆出来的样子。保留它不是为了用，是为了对照。
 *
 * <p>【解决什么问题】它不解决任何新问题——纯粹是"没有设计模式时最自然的写法"，
 * 用来和 {@link PricingService} 做 A/B 对照：同样的参数打进来，金额必须一致，
 * 但可维护性、可测试性、可扩展性完全不同。
 *
 * <p>【为什么这样设计（其实是"为什么不要这样写"）】它的具体问题（也是代码评审里最该指出的）：
 * <ol>
 *   <li>加一个"新客首单立减"就要改这个方法，所有策略的回归测试都得重跑；</li>
 *   <li>策略越多，方法越长，圈复杂度直接爆表，Code Review 只能看到 diff 看不到全貌；</li>
 *   <li>金额边界（空值、负数、精度）只能在每个分支里各写一遍，迟早漏；</li>
 *   <li>无法按渠道/租户动态装配策略，也无法单独做单测覆盖。</li>
 * </ol>
 * 对比策略模式版：那边每个分支是一个独立类，新增优惠是"加文件"，这里是"改文件"。
 *
 * <p>【怎么读这段代码】把 {@code price} 从上到下读一遍，数一下"新增一种优惠"要动几处：
 * 一个 else-if 分支 + 一处精度处理 + 一处 code 常量。然后再看 {@link VipPricingStrategy}，
 * 那边只需要一个新类。
 */
@Deprecated(since = "arch-lab", forRemoval = false)
@Service
public class LegacyPricingService {

    private static final Logger log = LoggerFactory.getLogger(LegacyPricingService.class);

    /**
     * 按 code 分支计算应付金额。
     *
     * @param code    策略标识，大小写不敏感，不能为 null
     * @param request 计价入参
     * @return 应付金额，保留两位小数、四舍五入
     * @throws BizException code 为空或未知时抛出（PARAM_INVALID）
     */
    @SuppressWarnings("java:S3776")
    public BigDecimal price(String code, PricingRequest request) {
        BigDecimal subtotal = request.subtotal();
        if (code == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "strategy 不能为空");
        }
        // 这就是"要改的文件"：任何新优惠都插在这里，所有人改同一行，冲突和回归成本都集中在这一处
        if ("NORMAL".equalsIgnoreCase(code)) {
            return subtotal.setScale(2, RoundingMode.HALF_UP);
        } else if ("VIP".equalsIgnoreCase(code)) {
            return subtotal.multiply(new BigDecimal("0.95")).setScale(2, RoundingMode.HALF_UP);
        } else if ("PROMOTION".equalsIgnoreCase(code)) {
            // 折扣率是魔法值，和 VipPricingStrategy 里的常量重复定义：
            // 一旦运营改折扣，两边都得改，漏一个就是资损
            if (subtotal.compareTo(new BigDecimal("300")) >= 0) {
                return subtotal.subtract(new BigDecimal("50")).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
            }
            return subtotal.setScale(2, RoundingMode.HALF_UP);
        }
        // 每个分支都自己 throw，看起来没漏，但新增分支时最容易忘的就是这里
        log.warn("【Legacy 计价】未知策略 code={}，请求被判为非法", code);
        throw new BizException(ErrorCode.PARAM_INVALID, "未知计价策略: " + code);
    }
}
