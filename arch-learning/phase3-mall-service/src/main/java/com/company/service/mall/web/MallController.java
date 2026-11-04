package com.company.service.mall.web;

import com.company.common.core.ApiResult;
import com.company.service.mall.domain.Order;
import com.company.service.mall.domain.Payment;
import com.company.service.mall.domain.Product;
import com.company.service.mall.service.MallStatsService;
import com.company.service.mall.service.OrderEventProjector;
import com.company.service.mall.service.OrderService;
import com.company.service.mall.service.OutboxRelay;
import com.company.service.mall.service.PaymentNotifier;
import com.company.service.mall.service.ProductService;
import com.company.service.mall.web.dto.CreateProductRequest;
import com.company.service.mall.web.dto.OrderView;
import com.company.service.mall.web.dto.PaymentView;
import com.company.service.mall.web.dto.PlaceOrderRequest;
import com.company.service.mall.web.dto.ProductView;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 商城对外接口：商品建档、下单、支付、取消、超时关单，以及三个运维触发点。
 *
 * <p>【解决什么问题】把"跨服务最终一致"这套设计暴露成可操作的 HTTP 入口：
 * 用户侧只负责下单/支付/取消，运维侧可以手动推动中继与关单，
 * 于是"消息没发出去""支付超时没关单"这些只能在深夜发生的状态，可以随时复现。
 *
 * <p>【为什么这样设计】Controller 只做三件事：参数转换、调用领域服务、组装视图，
 * 不写任何一致性逻辑 —— 幂等、加锁、补偿都必须在服务层，否则运维接口会成为绕过规则的旁路。
 * 运维接口（relay / notify / close-expired）放在 {@code /ops} 下：线上它们由定时任务驱动，
 * 暴露出来只是为了让实验能确定性地"手动推一轮"，真实的定时任务是同一份代码，
 * 不会因为多一个入口而出现两套逻辑。
 *
 * <p>【怎么读这段代码】下单 {@code POST /orders} → 服务层完成
 * "建单 + TCC 预占 + 写 outbox"；支付 {@code POST /orders/{no}/pay} → Confirm + 落流水 + 通知入队；
 * 查询 {@code GET /orders/{no}} 同时返回写端状态与投影状态，用来观察最终一致性的收敛时间。
 * 每个入口都打印请求参数，按 orderNo/requestId 串联即可还原整条链路的日志。
 */
@RestController
@RequestMapping
public class MallController {

    private static final Logger log = LoggerFactory.getLogger(MallController.class);

    private final ProductService productService;

    private final OrderService orderService;

    private final OrderEventProjector projector;

    private final OutboxRelay outboxRelay;

    private final PaymentNotifier paymentNotifier;

    private final MallStatsService statsService;

    public MallController(ProductService productService,
                          OrderService orderService,
                          OrderEventProjector projector,
                          OutboxRelay outboxRelay,
                          PaymentNotifier paymentNotifier,
                          MallStatsService statsService) {
        this.productService = productService;
        this.orderService = orderService;
        this.projector = projector;
        this.outboxRelay = outboxRelay;
        this.paymentNotifier = paymentNotifier;
        this.statsService = statsService;
    }

    /**
     * 商品建档并初始化库存。
     *
     * <p>幂等性：不幂等，重复提交会创建多件商品。
     *
     * @throws com.company.common.core.BizException 参数非法
     */
    @PostMapping("/products")
    public ApiResult<ProductView> createProduct(@Valid @RequestBody CreateProductRequest request) {
        log.info("【接口】创建商品: name={}, priceCents={}, stock={}",
                request.name(), request.priceCents(), request.stock());
        Product product = productService.create(request.name(), request.priceCents(), request.stock());
        return ApiResult.ok(ProductView.from(product, productService.stockOf(product.productId())));
    }

    /**
     * 商品列表。幂等：只读。
     */
    @GetMapping("/products")
    public ApiResult<List<ProductView>> products() {
        log.info("【接口】查询商品列表");
        return ApiResult.ok(productService.list().stream()
                .map(product -> ProductView.from(product, productService.stockOf(product.productId())))
                .toList());
    }

    /**
     * 商品详情。幂等：只读。
     *
     * @throws com.company.common.core.BizException 商品不存在
     */
    @GetMapping("/products/{productId}")
    public ApiResult<ProductView> product(@PathVariable("productId") long productId) {
        log.info("【接口】查询商品: productId={}", productId);
        Product product = productService.get(productId);
        return ApiResult.ok(ProductView.from(product, productService.stockOf(productId)));
    }

    /**
     * 下单。requestId 是幂等键：重复提交返回同一张订单。
     *
     * <p>幂等性：幂等（以 requestId 为准），详见 {@link OrderService#place}。
     *
     * @throws com.company.common.core.BizException 商品不存在、库存不足、并发冲突
     */
    @PostMapping("/orders")
    public ApiResult<OrderView> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        log.info("【接口】下单请求: requestId={}, userId={}, lines={}",
                request.requestId(), request.userId(), request.lines());
        List<OrderService.LineRequest> lines = request.lines().stream()
                .map(line -> new OrderService.LineRequest(line.productId(), line.quantity()))
                .toList();
        Order order = orderService.place(request.requestId(), request.userId(), lines);
        log.info("【接口】下单返回: requestId={}, orderNo={}", request.requestId(), order.orderNo());
        return ApiResult.ok(view(order));
    }

    /**
     * 订单查询同时返回"写端状态"和"投影状态"，用来观察最终一致性的收敛时间。
     *
     * <p>幂等：只读。
     *
     * @throws com.company.common.core.BizException 订单不存在
     */
    @GetMapping("/orders/{orderNo}")
    public ApiResult<OrderView> order(@PathVariable("orderNo") String orderNo) {
        log.info("【接口】查询订单: orderNo={}", orderNo);
        return ApiResult.ok(view(orderService.get(orderNo)));
    }

    /**
     * 支付。重复支付会补齐 Confirm/通知/事件，不会重复扣库存。
     *
     * <p>幂等性：幂等（按 orderNo），详见 {@link OrderService#pay}。
     *
     * @throws com.company.common.core.BizException 订单不存在或状态不可支付
     */
    @PostMapping("/orders/{orderNo}/pay")
    public ApiResult<PaymentView> pay(@PathVariable("orderNo") String orderNo) {
        log.info("【接口】支付请求: orderNo={}", orderNo);
        Payment payment = orderService.pay(orderNo);
        log.info("【接口】支付返回: orderNo={}, paymentNo={}", orderNo, payment.paymentNo());
        return ApiResult.ok(PaymentView.from(payment));
    }

    /**
     * 取消订单并归还库存。已取消时幂等返回。
     *
     * <p>幂等性：幂等（按 orderNo）。
     *
     * @param reason 取消原因，缺省为"用户取消"
     * @throws com.company.common.core.BizException 订单不存在或状态不可取消
     */
    @PostMapping("/orders/{orderNo}/cancel")
    public ApiResult<OrderView> cancel(@PathVariable("orderNo") String orderNo,
                                       @RequestParam(name = "reason", required = false) String reason) {
        log.info("【接口】取消订单: orderNo={}, reason={}", orderNo, reason);
        return ApiResult.ok(view(orderService.cancel(orderNo, reason)));
    }

    /**
     * 手动触发一轮超时关单（线上等价于定时任务）。
     *
     * <p>幂等性：可重复调用，已关闭的订单不会被重复处理。
     *
     * @return 本轮关闭的订单数
     */
    @PostMapping("/ops/orders/close-expired")
    public ApiResult<Integer> closeExpired() {
        log.info("【接口】手动触发超时关单");
        return ApiResult.ok(orderService.closeExpired());
    }

    /**
     * 手动触发一轮 outbox 投递。
     *
     * <p>幂等性：可重复调用，已 SENT 的消息不会被重复扫描。
     *
     * @return 本轮扫描/成功/重试/死信数量
     */
    @PostMapping("/ops/outbox/relay")
    public ApiResult<OutboxRelay.RelayReport> relayOutbox() {
        log.info("【接口】手动触发 outbox 中继");
        return ApiResult.ok(outboxRelay.relayOnce(Instant.now()));
    }

    /**
     * 手动触发一轮商户通知。
     *
     * <p>幂等性：可重复调用，已成功的任务不会被重复发送。
     *
     * @return 本轮扫描/成功/重试/死信数量
     */
    @PostMapping("/ops/notify")
    public ApiResult<PaymentNotifier.NotifyReport> notifyOnce() {
        log.info("【接口】手动触发支付通知");
        return ApiResult.ok(paymentNotifier.notifyOnce(Instant.now()));
    }

    /**
     * 全链路状态快照。幂等：只读。
     */
    @GetMapping("/stats")
    public ApiResult<MallStatsService.Snapshot> stats() {
        log.info("【接口】查询全链路状态快照");
        return ApiResult.ok(statsService.snapshot());
    }

    /**
     * 组装订单视图：写端状态来自订单表，投影状态来自事件消费侧，支付信息来自流水表。
     */
    private OrderView view(Order order) {
        PaymentView payment = orderService.paymentOf(order.orderNo()).map(PaymentView::from).orElse(null);
        Order.Status projected = projector.statusOf(order.orderNo()).orElse(null);
        return OrderView.from(order, payment, projected);
    }
}
