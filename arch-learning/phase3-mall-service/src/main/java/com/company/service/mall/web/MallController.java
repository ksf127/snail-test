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
        log.info("【商品】建档入参: name={}, priceCents={}, stock={}；本接口不幂等，重复提交会建出多件商品",
                request.name(), request.priceCents(), request.stock());
        Product product = productService.create(request.name(), request.priceCents(), request.stock());
        ProductService.StockSnapshot stock = productService.stockOf(product.productId());
        log.info("【商品】建档结果: productId={}, priceCents={}, 可用库存={}",
                product.productId(), product.priceCents(), stock.available());
        return ApiResult.ok(ProductView.from(product, stock));
    }

    /**
     * 商品列表。幂等：只读。
     */
    @GetMapping("/products")
    public ApiResult<List<ProductView>> products() {
        List<ProductView> views = productService.list().stream()
                .map(product -> ProductView.from(product, productService.stockOf(product.productId())))
                .toList();
        log.info("【商品】列表结果: count={}（只读，无副作用）", views.size());
        return ApiResult.ok(views);
    }

    /**
     * 商品详情。幂等：只读。
     *
     * @throws com.company.common.core.BizException 商品不存在
     */
    @GetMapping("/products/{productId}")
    public ApiResult<ProductView> product(@PathVariable("productId") long productId) {
        Product product = productService.get(productId);
        ProductService.StockSnapshot stock = productService.stockOf(productId);
        log.info("【商品】详情结果: productId={}, priceCents={}, 可用库存={}",
                productId, product.priceCents(), stock.available());
        return ApiResult.ok(ProductView.from(product, stock));
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
        log.info("【下单】接口入参: requestId={}（幂等键）, userId={}, lines={}；分支由服务层判定:"
                        + "幂等命中 → 直接返回历史单；库存不足/预占异常 → 先回滚再抛；锁冲突 → 未进入主流程",
                request.requestId(), request.userId(), request.lines());
        List<OrderService.LineRequest> lines = request.lines().stream()
                .map(line -> new OrderService.LineRequest(line.productId(), line.quantity()))
                .toList();
        // 记下请求进入时刻：新建订单的 createdAt 一定晚于它，更早则说明命中了 requestId 幂等键
        Instant requestedAt = Instant.now();
        Order order;
        try {
            order = orderService.place(request.requestId(), request.userId(), lines);
        } catch (RuntimeException e) {
            // 异常类型与消息原样抛出，这里只补接口视角的日志：失败原因写在 message 里（库存不足/商品不存在/锁定冲突）
            log.warn("【下单】接口失败: requestId={}, 原因={}；判读: 库存不足/预占异常会先由服务层 Cancel 回滚已完成行，"
                            + "商品不存在或锁冲突则未产生库存变更",
                    request.requestId(), e.getMessage());
            throw e;
        }
        boolean reused = order.createdAt().isBefore(requestedAt);
        String branch = reused
                ? (order.status() == Order.Status.CANCELLED
                        ? "幂等命中一张已取消的历史单（首次下单失败留下的单），本次未再预占库存"
                        : "幂等命中已有订单，本次未再预占库存、未重复写事件")
                : "新建订单，库存已预占，order.created 已写入 outbox";
        log.info("【下单】接口结果: requestId={}, orderNo={}, status={}, amount={}分, 幂等命中={}, 判读={}",
                request.requestId(), order.orderNo(), order.status(), order.amountCents(), reused, branch);
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
        Order order = orderService.get(orderNo);
        OrderView orderView = view(order);
        log.info("【订单】查询结果: orderNo={}, 写端状态={}, 投影状态={}, 支付流水={}；"
                        + "写端已终态而投影仍为旧值，说明 outbox 未投递完或消费端未跟上",
                orderNo, order.status(), orderView.projectedStatus(),
                orderView.payment() == null ? "无" : orderView.payment().paymentNo());
        return ApiResult.ok(orderView);
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
        log.info("【支付】接口入参: orderNo={}；分支由订单状态判定: CREATED → Confirm + 落流水 + 置 PAID；"
                + "已 PAID → 只幂等补齐 Confirm/通知/事件，不重复扣库存；其他状态 → 拒绝", orderNo);
        Payment payment = orderService.pay(orderNo);
        log.info("【支付】接口结果: orderNo={}, paymentNo={}, amount={}分, 订单终态=PAID；"
                        + "本次各步是否重复执行见【TCC-Confirm】与【支付】阶段日志",
                orderNo, payment.paymentNo(), payment.amountCents());
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
        log.info("【取消】接口入参: orderNo={}, reason={}；分支由订单状态判定: 已 CANCELLED 幂等返回，"
                + "CREATED 才归还库存并写 order.cancelled，其他状态直接拒绝", orderNo, reason);
        Order cancelled = orderService.cancel(orderNo, reason);
        log.info("【取消】接口结果: orderNo={}, status={}, closeReason={}；库存是否真正归还由【TCC-Cancel】分支日志决定",
                orderNo, cancelled.status(), cancelled.closeReason());
        return ApiResult.ok(view(cancelled));
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
        int closed = orderService.closeExpired();
        log.info("【超时关单】本轮结果: closed={}；只处理 CREATED 且超过 payTimeout 的订单，"
                + "抢不到锁或已被支付/取消的订单跳过，下一轮再扫", closed);
        return ApiResult.ok(closed);
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
        OutboxRelay.RelayReport report = outboxRelay.relayOnce(Instant.now());
        log.info("【Outbox 中继】本轮结果: 扫描={}, 投递成功={}, 待重试={}, 死信={}；"
                        + "重复触发不会重发已 SENT 的消息",
                report.picked(), report.sent(), report.retried(), report.dead());
        return ApiResult.ok(report);
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
        PaymentNotifier.NotifyReport report = paymentNotifier.notifyOnce(Instant.now());
        log.info("【支付通知】本轮结果: 扫描={}, 发送成功={}, 待重试={}, 死信={}；重复触发不会重发已成功的任务",
                report.picked(), report.succeeded(), report.retried(), report.dead());
        return ApiResult.ok(report);
    }

    /**
     * 全链路状态快照。幂等：只读。
     */
    @GetMapping("/stats")
    public ApiResult<MallStatsService.Snapshot> stats() {
        MallStatsService.Snapshot snapshot = statsService.snapshot();
        log.info("【状态快照】订单={}, 支付={}, outbox(待投递/已投递/死信)={}/{}/{}, "
                        + "通知(待发/成功/死信)={}/{}/{}, TCC(已Try/已Confirm/已Cancel)={}/{}/{}",
                snapshot.orders(), snapshot.payments(),
                snapshot.outboxPending(), snapshot.outboxSent(), snapshot.outboxDead(),
                snapshot.notifyPending(), snapshot.notifySucceeded(), snapshot.notifyDead(),
                snapshot.tccTried(), snapshot.tccConfirmed(), snapshot.tccCancelled());
        return ApiResult.ok(snapshot);
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
