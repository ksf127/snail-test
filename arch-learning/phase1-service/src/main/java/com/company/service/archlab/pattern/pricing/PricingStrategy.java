package com.company.service.archlab.pattern.pricing;

import java.math.BigDecimal;

/**
 * 计价策略（策略模式）。
 *
 * <p>【解决什么问题】"VIP 打 95 折、促销满 300 减 50、以后还会有新人首单、渠道专享……"
 * 这类需求天然会长出 if/else。每加一种优惠，就要动同一个方法、重跑所有策略的回归测试、
 * 还容易和同事改到同一行产生冲突。策略模式把"算法的选择"和"算法的实现"拆开。
 *
 * <p>【为什么这样设计】接口只留两个方法：一个标识（{@link #code()}）、一个计算（{@link #calculate}）。
 * 标识由实现自己声明，工厂据此建立映射，所以新增优惠只需要新增一个类，
 * 工厂、门面、Controller 全都不用改——这是"开闭原则"唯一真正可落地的形态。
 * 代价是类会变多，且策略之间无法共享中间状态，所以入参必须收敛成 {@link PricingRequest}。
 *
 * <p>【怎么读这段代码】先看 {@link #code()} 和 {@link #calculate} 的约定，
 * 再看三个实现类（Normal / Vip / Promotion）如何各自只关心一条规则，
 * 最后看 {@link PricingStrategyFactory} 怎么用 Spring 的 List 注入把它们自动收集起来。
 * 对照组是 {@link LegacyPricingService}：同一个需求的 if/else 版本。
 */
public interface PricingStrategy {

    /** 策略标识，必须全局唯一；由工厂做映射，接口里不要出现 if/switch。 */
    String code();

    /**
     * 按策略计算应付金额。
     *
     * @param request 计价入参，非空；构造时已保证数量与单价合法
     * @return 应付金额，保留两位小数、四舍五入；结果不会为负数
     */
    BigDecimal calculate(PricingRequest request);
}
