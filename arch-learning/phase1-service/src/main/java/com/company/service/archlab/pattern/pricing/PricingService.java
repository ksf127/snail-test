package com.company.service.archlab.pattern.pricing;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 计价门面：对外只暴露"给我策略 + 数量 + 单价，还我一个报价"。
 *
 * <p>【解决什么问题】调用方（Controller / 其他服务）不应该知道"有哪些策略、策略怎么被找到"。
 * 如果让调用方直接 new 策略或自己 switch，策略一多，调用方就会被污染成第二个工厂。
 *
 * <p>【为什么这样设计】这里刻意只做三件事：查策略、算金额、组装返回。
 * 没有缓存、没有降级、没有二次校验——那些属于真正的业务编排，放进来只会让"策略模式"这个实验变模糊。
 * 与 Legacy 版对照：入参、出参结构完全一致，只是把"选算法"交给了多态。
 *
 * <p>【怎么读这段代码】读 {@link #quote(String, PricingRequest)} 的三行即可；
 * 想追"策略从哪来"，跳到 {@link PricingStrategyFactory} 的构造器。
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    /**
     * 报价结果。
     *
     * @param strategy 实际命中的策略标识，方便调用方/排障确认"这笔钱是按哪条规则算的"
     * @param subtotal 优惠前小计
     * @param payable  优惠后应付
     */
    public record Quote(String strategy, BigDecimal subtotal, BigDecimal payable) {
    }

    private final PricingStrategyFactory factory;

    public PricingService(PricingStrategyFactory factory) {
        this.factory = factory;
    }

    /**
     * 按策略算一笔报价。
     *
     * @param strategyCode 策略标识，大小写不敏感；为空或未知时由工厂抛异常
     * @param request      计价入参，非空
     * @return 含策略名、小计与应付金额的报价
     * @throws com.company.common.core.BizException 策略为空或不存在时抛出（40001）
     */
    public Quote quote(String strategyCode, PricingRequest request) {
        PricingStrategy strategy = factory.of(strategyCode);
        BigDecimal payable = strategy.calculate(request);
        // 每次报价都要留痕：金额类问题排查时，"用了哪个策略、小计多少、最后收多少"缺一不可
        log.info("【计价】策略={}，数量={}，单价={}，小计={}，应付={}",
                strategy.code(), request.quantity(), request.unitPrice(),
                request.subtotal(), payable);
        return new Quote(strategy.code(), request.subtotal(), payable);
    }
}
