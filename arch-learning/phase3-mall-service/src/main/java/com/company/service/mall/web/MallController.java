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
 * <p>运维接口（relay / notify / close-expired）放在 {@code /ops} 下：
 * 线上它们由定时任务驱动，暴露出来只是为了让实验能确定性地"手动推一轮"，
 * 真实的定时任务是同一份代码，不会因为多一个入口而出现两套逻辑。
 */
@RestController
@RequestMapping
public class MallController {

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

    @PostMapping("/products")
    public ApiResult<ProductView> createProduct(@Valid @RequestBody CreateProductRequest request) {
        Product product = productService.create(request.name(), request.priceCents(), request.stock());
        return ApiResult.ok(ProductView.from(product, productService.stockOf(product.productId())));
    }

    @GetMapping("/products")
    public ApiResult<List<ProductView>> products() {
        return ApiResult.ok(productService.list().stream()
                .map(product -> ProductView.from(product, productService.stockOf(product.productId())))
                .toList());
    }

    @GetMapping("/products/{productId}")
    public ApiResult<ProductView> product(@PathVariable("productId") long productId) {
        Product product = productService.get(productId);
        return ApiResult.ok(ProductView.from(product, productService.stockOf(productId)));
    }

    @PostMapping("/orders")
    public ApiResult<OrderView> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        List<OrderService.LineRequest> lines = request.lines().stream()
                .map(line -> new OrderService.LineRequest(line.productId(), line.quantity()))
                .toList();
        Order order = orderService.place(request.requestId(), request.userId(), lines);
        return ApiResult.ok(view(order));
    }

    /**
     * 订单查询同时返回"写端状态"和"投影状态"，用来观察最终一致性的收敛时间。
     */
    @GetMapping("/orders/{orderNo}")
    public ApiResult<OrderView> order(@PathVariable("orderNo") String orderNo) {
        return ApiResult.ok(view(orderService.get(orderNo)));
    }

    @PostMapping("/orders/{orderNo}/pay")
    public ApiResult<PaymentView> pay(@PathVariable("orderNo") String orderNo) {
        Payment payment = orderService.pay(orderNo);
        return ApiResult.ok(PaymentView.from(payment));
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public ApiResult<OrderView> cancel(@PathVariable("orderNo") String orderNo,
                                       @RequestParam(name = "reason", required = false) String reason) {
        return ApiResult.ok(view(orderService.cancel(orderNo, reason)));
    }

    @PostMapping("/ops/orders/close-expired")
    public ApiResult<Integer> closeExpired() {
        return ApiResult.ok(orderService.closeExpired());
    }

    @PostMapping("/ops/outbox/relay")
    public ApiResult<OutboxRelay.RelayReport> relayOutbox() {
        return ApiResult.ok(outboxRelay.relayOnce(Instant.now()));
    }

    @PostMapping("/ops/notify")
    public ApiResult<PaymentNotifier.NotifyReport> notifyOnce() {
        return ApiResult.ok(paymentNotifier.notifyOnce(Instant.now()));
    }

    @GetMapping("/stats")
    public ApiResult<MallStatsService.Snapshot> stats() {
        return ApiResult.ok(statsService.snapshot());
    }

    private OrderView view(Order order) {
        PaymentView payment = orderService.paymentOf(order.orderNo()).map(PaymentView::from).orElse(null);
        Order.Status projected = projector.statusOf(order.orderNo()).orElse(null);
        return OrderView.from(order, payment, projected);
    }
}
