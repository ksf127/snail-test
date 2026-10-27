package com.company.service.archlab.pattern.pricing;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.springframework.stereotype.Component;

/**
 * 计价策略工厂：把"策略标识 → 实现"的映射在启动时一次性建好。
 *
 * <p>为什么不在工厂里写 switch：
 * switch 每加一个分支就要改工厂、改测试、跟别人抢同一个文件；
 * 用 Spring 注入 {@code List<PricingStrategy>} 后，新增策略对工厂零改动。
 *
 * <p>重复 code 必须启动即失败：这是配置错误，不能留到线上第一次调用才暴露。
 */
@Component
public class PricingStrategyFactory {

    private final Map<String, PricingStrategy> strategies;

    public PricingStrategyFactory(List<PricingStrategy> strategyList) {
        this.strategies = strategyList.stream().collect(Collectors.toUnmodifiableMap(
                strategy -> strategy.code().toUpperCase(Locale.ROOT),
                Function.identity(),
                (left, right) -> {
                    throw new IllegalStateException("计价策略 code 重复: " + left.code());
                }));
    }

    public PricingStrategy of(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "strategy 不能为空");
        }
        PricingStrategy strategy = strategies.get(code.toUpperCase(Locale.ROOT));
        if (strategy == null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "未知计价策略: " + code + "，可选: " + strategies.keySet());
        }
        return strategy;
    }

    public Set<String> codes() {
        return strategies.keySet();
    }
}
