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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设计模式实验入口：每个模式都同时暴露"模式实现"与"坏味道实现"，方便直接对照。
 *
 * <p>【解决什么问题】设计模式最大的问题是"看懂了但说不清好在哪"。
 * 这里把同一需求的两套实现（策略/责任链/状态机 vs 一坨 if）放在相邻的两个端点里，
 * 用同样的参数打进去，差异可以直接观察：返回结构、可扩展点、排障信息。
 *
 * <p>【为什么这样设计】模式类都通过构造器注入，Controller 不 new 任何东西，
 * 这样"容器里注册了哪些策略/校验节点"完全由 Spring 决定（见 /pricing/strategies、/validation/chain），
 * 新增实现不需要改这里。
 *
 * <p>【怎么读这段代码】按模式分三组读：
 * 计价（/pricing 与 /pricing/legacy）、校验（/validation 与 /validation/legacy）、
 * 状态机（/state-machine 与 /state-machine/allowed）。
 * 每组先看模式版，再看 legacy 版，最后对照两者的入参和返回。
 */
@RestController
@RequestMapping("/patterns")
public class PatternController {

    private static final Logger log = LoggerFactory.getLogger(PatternController.class);

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
     *
     * @param strategy  策略标识，大小写不敏感，未知标识返回 40001
     * @param quantity  购买数量，必须大于 0
     * @param unitPrice 单价，不能为负
     * @return 含策略名、小计与应付金额的报价
     */
    @GetMapping("/pricing")
    public ApiResult<PricingService.Quote> pricing(
            @RequestParam(name = "strategy", defaultValue = "NORMAL") String strategy,
            @RequestParam(name = "quantity", defaultValue = "1") int quantity,
            @RequestParam(name = "unitPrice", defaultValue = "100.00") BigDecimal unitPrice) {
        log.info("【设计模式接口】/pricing 收到请求，strategy={}，quantity={}，unitPrice={}",
                strategy, quantity, unitPrice);
        return run(() -> pricingService.quote(strategy, new PricingRequest(quantity, unitPrice)));
    }

    /**
     * 反面教材：if/else 计价。同样的参数打进来，金额应与上方一致。
     *
     * @param strategy  策略标识
     * @param quantity  购买数量
     * @param unitPrice 单价
     * @return 应付金额（裸 BigDecimal，没有策略名与小计，信息量比模式版少）
     */
    @GetMapping("/pricing/legacy")
    public ApiResult<BigDecimal> pricingLegacy(
            @RequestParam(name = "strategy", defaultValue = "NORMAL") String strategy,
            @RequestParam(name = "quantity", defaultValue = "1") int quantity,
            @RequestParam(name = "unitPrice", defaultValue = "100.00") BigDecimal unitPrice) {
        log.info("【设计模式接口】/pricing/legacy 收到请求，strategy={}，quantity={}，unitPrice={}",
                strategy, quantity, unitPrice);
        return run(() -> legacyPricingService.price(strategy, new PricingRequest(quantity, unitPrice)));
    }

    /**
     * 当前容器里注册了哪些计价策略——新增策略时，这里会自动多一个值。
     *
     * @return 全部已注册策略标识（大写）
     */
    @GetMapping("/pricing/strategies")
    public ApiResult<Set<String>> pricingStrategies() {
        // 装配类接口，调用频率低；打 info 便于确认"新策略是否真的被扫描进来了"
        log.info("【设计模式接口】/pricing/strategies 收到请求");
        return ApiResult.ok(pricingStrategyFactory.codes());
    }

    /**
     * 责任链校验，返回实际执行过的节点；校验失败抛 BizException，短路后续节点。
     *
     * @param userId     用户标识，blocked 前缀视为封禁
     * @param skuId      商品标识，soldout 前缀视为无库存
     * @param quantity   购买数量，允许 [1, 100]
     * @param unitPrice  单价，当前链路未使用，保留以便扩展金额校验
     * @param couponCode 优惠券码，当前链路未使用，保留以便扩展优惠校验
     * @return 实际执行过的节点名，按执行顺序排列
     */
    @GetMapping("/validation")
    public ApiResult<List<String>> validation(
            @RequestParam(name = "userId", defaultValue = "u-1") String userId,
            @RequestParam(name = "skuId", defaultValue = "sku-1") String skuId,
            @RequestParam(name = "quantity", defaultValue = "2") int quantity,
            @RequestParam(name = "unitPrice", defaultValue = "99.00") BigDecimal unitPrice,
            @RequestParam(name = "couponCode", defaultValue = "") String couponCode) {
        log.info("【设计模式接口】/validation 收到请求，userId={}，skuId={}，quantity={}，couponCode={}",
                userId, skuId, quantity, couponCode);
        return run(() -> validationChain.validate(
                new OrderContext(userId, skuId, quantity, unitPrice, couponCode)));
    }

    /**
     * 反面教材：五段 if 塞在一个方法里，排障时看不到"执行到哪一步"。
     *
     * @param userId     用户标识
     * @param skuId      商品标识
     * @param quantity   购买数量
     * @param unitPrice  单价
     * @param couponCode 优惠券码
     * @return 固定返回 "PASS"，因为这种方法除了抛异常没有任何过程信息
     */
    @GetMapping("/validation/legacy")
    public ApiResult<String> validationLegacy(
            @RequestParam(name = "userId", defaultValue = "u-1") String userId,
            @RequestParam(name = "skuId", defaultValue = "sku-1") String skuId,
            @RequestParam(name = "quantity", defaultValue = "2") int quantity,
            @RequestParam(name = "unitPrice", defaultValue = "99.00") BigDecimal unitPrice,
            @RequestParam(name = "couponCode", defaultValue = "") String couponCode) {
        log.info("【设计模式接口】/validation/legacy 收到请求，userId={}，skuId={}，quantity={}",
                userId, skuId, quantity);
        return run(() -> {
            legacyValidationService.validate(new OrderContext(userId, skuId, quantity, unitPrice, couponCode));
            return "PASS";
        });
    }

    /**
     * 查看责任链装配顺序。
     *
     * @return 节点名列表，顺序即实际执行顺序（由 {@code @Order} 决定）
     */
    @GetMapping("/validation/chain")
    public ApiResult<List<String>> validationChain() {
        log.info("【设计模式接口】/validation/chain 收到请求");
        return ApiResult.ok(validationChain.chain());
    }

    /**
     * 状态机迁移，例如：{@code ?state=CREATED&event=PAY}。
     *
     * @param state 当前订单状态
     * @param event 触发的动作
     * @return 迁移结果（from / event / to）
     */
    @GetMapping("/state-machine")
    public ApiResult<OrderStateMachine.Transition> stateMachine(
            @RequestParam(name = "state") OrderState state,
            @RequestParam(name = "event") OrderEvent event) {
        log.info("【设计模式接口】/state-machine 收到请求，state={}，event={}", state, event);
        return run(() -> orderStateMachine.transit(state, event));
    }

    /**
     * 当前状态允许的动作——前端按钮、接口文档都可以直接读它。
     *
     * @param state 当前订单状态
     * @return 该状态下合法的动作集合，可能为空
     */
    @GetMapping("/state-machine/allowed")
    public ApiResult<Set<OrderEvent>> allowedEvents(@RequestParam(name = "state") OrderState state) {
        log.info("【设计模式接口】/state-machine/allowed 收到请求，state={}", state);
        return run(() -> orderStateMachine.allowedEvents(state));
    }

    /**
     * 与并发实验台一致：把 record 构造器抛出的 IllegalArgumentException 翻译成 40001。
     *
     * @param experiment 实际执行实验的代码块
     * @param <T>        实验结果类型
     * @return 包装成统一响应体的结果
     * @throws BizException 参数非法时抛出
     */
    private <T> ApiResult<T> run(Supplier<T> experiment) {
        try {
            return ApiResult.ok(experiment.get());
        } catch (IllegalArgumentException e) {
            log.warn("【设计模式接口】参数非法，已拒绝执行：{}", e.getMessage());
            throw new BizException(ErrorCode.PARAM_INVALID, e.getMessage());
        }
    }
}
