package com.company.service.archlab.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.company.common.core.ApiResult;
import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.service.archlab.pattern.pricing.LegacyPricingService;
import com.company.service.archlab.pattern.pricing.PricingRequest;
import com.company.service.archlab.pattern.pricing.PricingService;
import com.company.service.archlab.pattern.pricing.PricingStrategyFactory;
import com.company.service.archlab.pattern.statemachine.OrderEvent;
import com.company.service.archlab.pattern.statemachine.OrderState;
import com.company.service.archlab.pattern.statemachine.OrderStateMachine;
import com.company.service.archlab.pattern.validation.LegacyValidationService;
import com.company.service.archlab.pattern.validation.OrderContext;
import com.company.service.archlab.pattern.validation.ValidationChain;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设计模式实验入口：每个模式都同时暴露"模式实现"与"坏味道实现"，方便直接对照。
 *
 * <p>默认参数都是能跑通的合法值，改一个参数就能触发对应分支。
 */
@RestController
@RequestMapping("/patterns")
public class PatternController {

    private final PricingService pricingService;

    private final LegacyPricingService legacyPricingService;

    private final PricingStrategyFactory pricingStrategyFactory;

    private final ValidationChain validationChain;

    private final LegacyValidationService legacyValidationService;

    private final OrderStateMachine orderStateMachine;

    public PatternController(PricingService pricingService,
                             LegacyPricingService legacyPricingService,
                             PricingStrategyFactory pricingStrategyFactory,
                             ValidationChain validationChain,
                             LegacyValidationService legacyValidationService,
                             OrderStateMachine orderStateMachine) {
        this.pricingService = pricingService;
        this.legacyPricingService = legacyPricingService;
        this.pricingStrategyFactory = pricingStrategyFactory;
        this.validationChain = validationChain;
        this.legacyValidationService = legacyValidationService;
        this.orderStateMachine = orderStateMachine;
    }

    /**
     * 策略模式计价，例如：{@code ?strategy=VIP&quantity=2&unitPrice=100}。
     */
    @GetMapping("/pricing")
    public ApiResult<PricingService.Quote> pricing(
            @RequestParam(name = "strategy", defaultValue = "NORMAL") String strategy,
            @RequestParam(name = "quantity", defaultValue = "1") int quantity,
            @RequestParam(name = "unitPrice", defaultValue = "100.00") BigDecimal unitPrice) {
        return run(() -> pricingService.quote(strategy, new PricingRequest(quantity, unitPrice)));
    }

    /**
     * 反面教材：if/else 计价。同样的参数打进来，金额应与上方一致。
     */
    @GetMapping("/pricing/legacy")
    public ApiResult<BigDecimal> pricingLegacy(
            @RequestParam(name = "strategy", defaultValue = "NORMAL") String strategy,
            @RequestParam(name = "quantity", defaultValue = "1") int quantity,
            @RequestParam(name = "unitPrice", defaultValue = "100.00") BigDecimal unitPrice) {
        return run(() -> legacyPricingService.price(strategy, new PricingRequest(quantity, unitPrice)));
    }

    /**
     * 当前容器里注册了哪些计价策略——新增策略时，这里会自动多一个值。
     */
    @GetMapping("/pricing/strategies")
    public ApiResult<Set<String>> pricingStrategies() {
        return ApiResult.ok(pricingStrategyFactory.codes());
    }

    /**
     * 责任链校验，返回实际执行过的节点；校验失败抛 BizException，短路后续节点。
     */
    @GetMapping("/validation")
    public ApiResult<List<String>> validation(
            @RequestParam(name = "userId", defaultValue = "u-1") String userId,
            @RequestParam(name = "skuId", defaultValue = "sku-1") String skuId,
            @RequestParam(name = "quantity", defaultValue = "2") int quantity,
            @RequestParam(name = "unitPrice", defaultValue = "99.00") BigDecimal unitPrice,
            @RequestParam(name = "couponCode", defaultValue = "") String couponCode) {
        return run(() -> validationChain.validate(
                new OrderContext(userId, skuId, quantity, unitPrice, couponCode)));
    }

    /**
     * 反面教材：五段 if 塞在一个方法里，排障时看不到"执行到哪一步"。
     */
    @GetMapping("/validation/legacy")
    public ApiResult<String> validationLegacy(
            @RequestParam(name = "userId", defaultValue = "u-1") String userId,
            @RequestParam(name = "skuId", defaultValue = "sku-1") String skuId,
            @RequestParam(name = "quantity", defaultValue = "2") int quantity,
            @RequestParam(name = "unitPrice", defaultValue = "99.00") BigDecimal unitPrice,
            @RequestParam(name = "couponCode", defaultValue = "") String couponCode) {
        return run(() -> {
            legacyValidationService.validate(new OrderContext(userId, skuId, quantity, unitPrice, couponCode));
            return "PASS";
        });
    }

    /**
     * 查看责任链装配顺序。
     */
    @GetMapping("/validation/chain")
    public ApiResult<List<String>> validationChain() {
        return ApiResult.ok(validationChain.chain());
    }

    /**
     * 状态机迁移，例如：{@code ?state=CREATED&event=PAY}。
     */
    @GetMapping("/state-machine")
    public ApiResult<OrderStateMachine.Transition> stateMachine(
            @RequestParam(name = "state") OrderState state,
            @RequestParam(name = "event") OrderEvent event) {
        return run(() -> orderStateMachine.transit(state, event));
    }

    /**
     * 当前状态允许的动作——前端按钮、接口文档都可以直接读它。
     */
    @GetMapping("/state-machine/allowed")
    public ApiResult<Set<OrderEvent>> allowedEvents(@RequestParam(name = "state") OrderState state) {
        return run(() -> orderStateMachine.allowedEvents(state));
    }

    /**
     * 与并发实验台一致：把 record 构造器抛出的 IllegalArgumentException 翻译成 40001。
     */
    private <T> ApiResult<T> run(Supplier<T> experiment) {
        try {
            return ApiResult.ok(experiment.get());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
    }
}
