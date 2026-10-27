package com.company.service.mall.service;

import com.company.common.core.BizException;
import com.company.service.mall.domain.Order;
import com.company.service.mall.domain.Payment;
import com.company.service.mall.domain.Product;
import com.company.service.mall.infra.InMemoryMessagePublisher;
import com.company.service.mall.infra.InMemoryNotificationGateway;
import com.company.service.mall.infra.InMemoryNotifyTaskRepository;
import com.company.service.mall.infra.InMemoryOrderRepository;
import com.company.service.mall.infra.InMemoryOutboxMessageRepository;
import com.company.service.mall.infra.InMemoryPaymentRepository;
import com.company.service.mall.infra.InMemoryProductRepository;
import com.company.service.mall.infra.InMemoryStockRepository;
import com.company.service.mall.infra.InMemoryTccTransactionRepository;
import com.company.service.mall.support.MallProperties;
import com.company.starter.lock.DistributedLock;
import com.company.starter.lock.InMemoryDistributedLock;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 全链路：下单（TCC Try）→ outbox 中继 → 消费端投影 → 支付（Confirm）→ 最大努力通知。
 *
 * <p>这里不启动 Spring，而是按真实 Bean 依赖手工装配：
 * 实验要看的是状态机与一致性，而不是容器能否启动。
 */
class MallFlowTest {

    private MallProperties properties;

    private InMemoryProductRepository products;

    private InMemoryStockRepository stocks;

    private InMemoryOrderRepository orders;

    private InMemoryPaymentRepository payments;

    private InMemoryOutboxMessageRepository outbox;

    private InMemoryNotifyTaskRepository notifyTasks;

    private InMemoryTccTransactionRepository tccTransactions;

    private InMemoryMessagePublisher publisher;

    private InMemoryNotificationGateway gateway;

    private ProductService productService;

    private StockTccService tccService;

    private OrderService orderService;

    private PaymentNotifier notifier;

    private OutboxRelay relay;

    private OrderEventProjector projector;

    private MallStatsService statsService;

    @BeforeEach
    void setUp() {
        properties = new MallProperties();
        DistributedLock lock = new InMemoryDistributedLock("test:");
        products = new InMemoryProductRepository();
        stocks = new InMemoryStockRepository();
        orders = new InMemoryOrderRepository();
        payments = new InMemoryPaymentRepository();
        outbox = new InMemoryOutboxMessageRepository();
        notifyTasks = new InMemoryNotifyTaskRepository();
        tccTransactions = new InMemoryTccTransactionRepository();
        publisher = new InMemoryMessagePublisher();
        gateway = new InMemoryNotificationGateway();
        projector = new OrderEventProjector(new ObjectMapper());
        publisher.subscribe(projector);
        productService = new ProductService(products, stocks);
        tccService = new StockTccService(stocks, tccTransactions, lock, properties);
        notifier = new PaymentNotifier(notifyTasks, gateway, properties);
        orderService = new OrderService(orders, payments, products, tccService, outbox, notifier,
                lock, properties, new ObjectMapper());
        relay = new OutboxRelay(outbox, publisher, properties);
        statsService = new MallStatsService(products, stocks, orders, payments, outbox, notifyTasks,
                tccTransactions, tccService, projector, publisher, gateway, lock);
    }

    @Test
    @DisplayName("支付成功：事件按时序投影到 PAID，通知送达，TCC 分支全部 Confirm")
    void happyPathConverges() {
        Product product = productService.create("苹果", 500L, 10);
        Order order = orderService.place("req-1", 42L,
                List.of(new OrderService.LineRequest(product.productId(), 3)));

        // 写端已落单，但消费端还没收到事件：这正是最终一致性的不一致窗口
        assertThat(orderService.get(order.orderNo()).status()).isEqualTo(Order.Status.CREATED);
        assertThat(projector.statusOf(order.orderNo())).isEmpty();
        assertThat(statsService.snapshot().outboxPending()).isEqualTo(1);

        relay.relayOnce(Instant.now());
        assertThat(projector.statusOf(order.orderNo())).contains(Order.Status.CREATED);

        Payment payment = orderService.pay(order.orderNo());
        relay.relayOnce(Instant.now());
        notifier.notifyOnce(Instant.now());

        assertThat(projector.statusOf(order.orderNo())).contains(Order.Status.PAID);

        MallStatsService.Snapshot snapshot = statsService.snapshot();
        assertThat(snapshot.ordersPaid()).isEqualTo(1);
        assertThat(snapshot.payments()).isEqualTo(1);
        assertThat(snapshot.outboxSent()).isEqualTo(2);
        assertThat(snapshot.outboxPending()).isZero();
        assertThat(snapshot.notifySucceeded()).isEqualTo(1);
        assertThat(snapshot.tccConfirmed()).isEqualTo(1);
        assertThat(snapshot.projector().paid()).isEqualTo(1);
        assertThat(snapshot.publisherProvider()).isEqualTo("memory");
        assertThat(snapshot.notificationProvider()).isEqualTo("memory");
        assertThat(snapshot.lockProvider()).isEqualTo("memory");
        assertThat(gateway.delivered()).containsExactly(payment.paymentNo() + "->merchant:42:1500");
    }

    @Test
    @DisplayName("库存不足：订单取消、库存不动、消费端最终也看到 CANCELLED")
    void failurePathKeepsStockIntact() {
        Product product = productService.create("苹果", 500L, 1);

        assertThatThrownBy(() -> orderService.place("req-2", 42L,
                List.of(new OrderService.LineRequest(product.productId(), 2))))
                .isInstanceOf(BizException.class);

        Order order = orders.findByRequestId("req-2").orElseThrow();
        assertThat(order.status()).isEqualTo(Order.Status.CANCELLED);
        assertThat(stocks.findById(product.productId()).orElseThrow().available()).isEqualTo(1);
        assertThat(stocks.findById(product.productId()).orElseThrow().reserved()).isZero();

        relay.relayOnce(Instant.now());
        assertThat(projector.statusOf(order.orderNo())).contains(Order.Status.CANCELLED);

        MallStatsService.Snapshot snapshot = statsService.snapshot();
        assertThat(snapshot.ordersCancelled()).isEqualTo(1);
        assertThat(snapshot.tccTried()).isZero();
        assertThat(snapshot.tccCancelled()).isEqualTo(1);
        assertThat(snapshot.tcc().emptyRollbacks()).isEqualTo(1);
        assertThat(snapshot.notifyPending()).isZero();
    }

    @Test
    @DisplayName("Broker 短时不可用：outbox 重试后两条事件都送达，投影顺序不乱")
    void brokerOutageThenRecovered() {
        Product product = productService.create("苹果", 500L, 10);
        Order order = orderService.place("req-3", 42L,
                List.of(new OrderService.LineRequest(product.productId(), 1)));
        orderService.pay(order.orderNo());

        publisher.failNext(2);
        OutboxRelay.RelayReport failed = relay.relayOnce(Instant.now());
        assertThat(failed).isEqualTo(new OutboxRelay.RelayReport(2, 0, 2, 0));
        assertThat(projector.statusOf(order.orderNo())).isEmpty();

        OutboxRelay.RelayReport recovered = relay.relayOnce(Instant.now().plusSeconds(1));
        assertThat(recovered).isEqualTo(new OutboxRelay.RelayReport(2, 2, 0, 0));
        assertThat(projector.statusOf(order.orderNo())).contains(Order.Status.PAID);
        assertThat(statsService.snapshot().outboxSent()).isEqualTo(2);
    }

    @Test
    @DisplayName("至少一次投递：重复消息被消费端按消息 ID 去重")
    void duplicateDeliveryIsIdempotent() {
        Product product = productService.create("苹果", 500L, 10);
        Order order = orderService.place("req-4", 42L,
                List.of(new OrderService.LineRequest(product.productId(), 1)));
        relay.relayOnce(Instant.now());

        publisher.publish(outbox.findAll().get(0));

        assertThat(projector.statusOf(order.orderNo())).contains(Order.Status.CREATED);
        assertThat(projector.snapshot().created()).isEqualTo(1);
        assertThat(projector.snapshot().duplicates()).isEqualTo(1);
    }
}
