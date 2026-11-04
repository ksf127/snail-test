package com.company.service.archlab.pattern.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 原价：无优惠。
 *
 * <p>【解决什么问题】它是策略体系里的"兜底实现"：既让工厂永远能返回一个非空策略，
 * 也让"不打折"这件事有明确代码位置，而不是靠"没有命中任何分支"这种隐式行为。
 *
 * <p>【为什么这样设计】看起来"什么都不做"也要单独一个类有点重，但正是这个类让
 * NORMAL 从"if/else 的默认分支"变成可被替换、可被单独测试的对象；
 * 以后运营要改"原价也抹零"，只需要改这一个文件。
 * 如果沿用 Legacy 写法，默认分支藏在 {@code else} 里，改错了没人知道影响了谁。
 *
 * <p>【怎么读这段代码】code() 声明标识，calculate() 只做"小计 + 两位小数"。
 * 注意 setScale 必须带 RoundingMode，否则遇到 0.005 这类值会直接抛 ArithmeticException。
 */
@Component
public class NormalPricingStrategy implements PricingStrategy {

    private static final Logger log = LoggerFactory.getLogger(NormalPricingStrategy.class);

    @Override
    public String code() {
        return "NORMAL";
    }

    /**
     * 原价策略：不做任何折扣，只统一金额精度。
     *
     * @param request 计价入参，非空
     * @return 小计金额，保留两位小数、四舍五入
     */
    @Override
    public BigDecimal calculate(PricingRequest request) {
        // 显式指定舍入模式：BigDecimal 在小数位溢出时默认抛异常，不能省
        BigDecimal payable = request.subtotal().setScale(2, RoundingMode.HALF_UP);
        // 策略内部是高频路径（每次下单都会走），只打 debug，不打 info
        log.debug("【NORMAL 计价】小计={}，应付={}", request.subtotal(), payable);
        return payable;
    }
}
