package com.company.service.archlab.pattern.pricing;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 计价策略工厂：把"策略标识 → 实现"的映射在启动时一次性建好。
 *
 * <p>【解决什么问题】策略有了实现类之后，需要一个地方按 code 找到它。
 * 如果调用方自己判断，就会退化成 switch；如果每次调用都遍历 List 找 code，就是 O(n) 且类型不安全。
 *
 * <p>【为什么这样设计】为什么不在工厂里写 switch：
 * switch 每加一个分支就要改工厂、改测试、跟别人抢同一个文件；
 * 用 Spring 注入 {@code List<PricingStrategy>} 后，新增策略对工厂零改动。
 * <p>为什么用不可变 Map：装配发生在启动期，运行期只读，所以不需要任何同步；
 * 用 {@code toUnmodifiableMap} 把"不许再改"固化成类型约束，避免后来者往里塞值。
 * <p>重复 code 必须启动即失败：这是配置错误，不能留到线上第一次调用才暴露。
 *
 * <p>【怎么读这段代码】先看构造器怎么把 List 转成 Map（重点是第三个参数——重复 key 的处理），
 * 再看 {@link #of(String)} 的两类失败：空 code、未知 code，都翻译成 40001。
 * 想看效果就调 {@code GET /patterns/pricing/strategies}，新增一个 @Component 后它会自动多一项。
 */
@Component
public class PricingStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(PricingStrategyFactory.class);

    private final Map<String, PricingStrategy> strategies;

    /**
     * 构造时一次性建立映射，之后整个生命周期只读。
     *
     * @param strategyList Spring 注入的全部 {@link PricingStrategy} 实现，顺序不保证
     * @throws IllegalStateException 两个策略声明了同一个 code 时抛出，让应用启动失败
     */
    public PricingStrategyFactory(List<PricingStrategy> strategyList) {
        this.strategies = strategyList.stream().collect(Collectors.toUnmodifiableMap(
                // 统一转大写：对外允许 strategy=vip 这种写法，避免大小写造成"策略不存在"的假故障
                strategy -> strategy.code().toUpperCase(Locale.ROOT),
                Function.identity(),
                (left, right) -> {
                    // 合并函数只在 key 冲突时触发；这里直接抛异常，
                    // 因为策略 code 重复属于配置错误，宁可启动失败也不要线上按不确定的顺序命中
                    throw new IllegalStateException("计价策略 code 重复: " + left.code());
                }));
        // 启动日志把全部策略打印一遍：部署后第一眼就能确认新策略是否被扫描到
        log.info("【计价策略工厂】初始化完成，共 {} 个策略: {}", strategies.size(), strategies.keySet());
    }

    /**
     * 按标识查找策略。
     *
     * @param code 策略标识，大小写不敏感，可以是 {@code null}
     * @return 对应的策略实现，非空
     * @throws BizException code 为空或不存在时抛出，错误码 PARAM_INVALID
     */
    public PricingStrategy of(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "strategy 不能为空");
        }
        PricingStrategy strategy = strategies.get(code.toUpperCase(Locale.ROOT));
        if (strategy == null) {
            // 把可选值带进日志和异常信息：调用方看报错就能改对参数，不用再去翻代码
            log.warn("【计价策略工厂】未知策略 code={}，可选值={}", code, strategies.keySet());
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "未知计价策略: " + code + "，可选: " + strategies.keySet());
        }
        // 单次调用级别的日志用 debug：这里是高频入口，info 会在促销期间刷满磁盘
        log.debug("【计价策略工厂】命中策略 code={}，实现={}",
                code, strategy.getClass().getSimpleName());
        return strategy;
    }

    /**
     * 全部已注册策略标识，用于对外暴露"系统支持哪些优惠"。
     *
     * @return 不可变集合，key 已统一为大写
     */
    public Set<String> codes() {
        return strategies.keySet();
    }
}
