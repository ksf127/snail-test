package com.company.service.mall.service;

import com.company.common.core.BizException;
import com.company.service.mall.domain.Order;
import com.company.service.mall.domain.Payment;
import com.company.service.mall.domain.Product;
import com.company.service.mall.infra.InMemoryNotifyTaskRepository;
import com.company.service.mall.infra.InMemoryOrderRepository;
import com.company.service.mall.infra.InMemoryOutboxMessageRepository;
import com.company.service.mall.infra.InMemoryPaymentRepository;
import com.company.service.mall.infra.InMemoryProductRepository;
import com.company.service.mall.infra.InMemoryStockRepository;
import com.company.service.mall.infra.InMemoryTccTransactionRepository;
import com.company.service.mall.infra.InMemoryNotificationGateway;
import com.company.service.mall.infra.OutboxMessageRepository;
import com.company.service.mall.support.MallProperties;
import com.company.starter.lock.DistributedLock;
import com.company.starter.lock.InMemoryDistributedLock;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 下单 / 支付 / 取消 / 关单的编排：幂等、补偿、状态机边界。
 */
class OrderServiceTest {

    private MallProperties properties;

    private InMemoryProductRepository products;

    private InMemoryStockRepository stocks;

    private InMemoryOrderRepository orders;

    private InMemoryPaymentRepository payments;

    private InMemoryOutboxMessageRepository outbox;

    private InMemoryNotifyTaskRepository notifyTasks;

    private InMemoryTccTransactionRepository tccTransactions;

    private ProductService productService;

    private StockTccService tccService;

    private OrderService orderService;

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
        productService = new ProductService(products, stocks);
        tccService = new StockTccService(stocks, tccTransactions, lock, properties);
        PaymentNotifier notifier = new PaymentNotifier(notifyTasks, new InMemoryNotificationGateway(), properties);
        orderService = new OrderService(orders, payments, products, tccService, outbox, notifier,
                lock, properties, new ObjectMapper());
    }

    @Test
    @DisplayName("同一个 requestId 重复下单返回同一张订单，库存只预占一次")
    void placeIsIdempotentByRequestId() {
        long productId = productService.create("苹果", 500L, 10).productId();
        List<OrderService.LineRequest> lines = List.of(new OrderService.LineRequest(productId, 3));

        Order first = orderService.place("req-1", 42L, lines);
        Order second = orderService.place("req-1", 42L, lines);

        assertThat(second.orderNo()).isEqualTo(first.orderNo());
        assertThat(orders.count()).isEqualTo(1);
        assertThat(stocks.findById(productId).orElseThrow().available()).isEqualTo(7);
        assertThat(stocks.findById(productId).orElseThrow().reserved()).isEqualTo(3);
        assertThat(outbox.findAll()).hasSize(1);
        assertThat(outbox.findAll().get(0).topic()).isEqualTo("order.created");
    }

    @Test
    @DisplayName("库存不足：订单落为 CANCELLED，库存原样归还，落取消事件")
    void insufficientStockAbortsOrder() {
        long productId = productService.create("苹果", 500L, 1).productId();
        List<OrderService.LineRequest> lines = List.of(new OrderService.LineRequest(productId, 2));

        assertThatThrownBy(() -> orderService.place("req-2", 42L, lines))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("库存不足");

        Order order = orders.findByRequestId("req-2").orElseThrow();
        assertThat(order.status()).isEqualTo(Order.Status.CANCELLED);
        assertThat(stocks.findById(productId).orElseThrow().available()).isEqualTo(1);
        assertThat(stocks.findById(productId).orElseThrow().reserved()).isZero();
        assertThat(outbox.findAll()).extracting(message -> message.topic())
                .containsExactly("order.cancelled");
        // 失败行没有 Try 记录，Cancel 走空回滚留痕
        assertThat(tccTransactions.count()).isEqualTo(1);
        assertThat(tccService.stats().emptyRollbacks()).isEqualTo(1);
    }

    @Test
    @DisplayName("支付 Confirm 预占、生成流水、投递事件与通知；重复支付全部幂等")
    void payConfirmsAndIsIdempotent() {
        long productId = productService.create("苹果", 500L, 10).productId();
        Order order = orderService.place("req-3", 42L,
                List.of(new OrderService.LineRequest(productId, 3)));

        Payment paid = orderService.pay(order.orderNo());

        assertThat(paid.amountCents()).isEqualTo(1500L);
        assertThat(orderService.get(order.orderNo()).status()).isEqualTo(Order.Status.PAID);
        assertThat(stocks.findById(productId).orElseThrow().reserved()).isZero();
        assertThat(stocks.findById(productId).orElseThrow().available()).isEqualTo(7);
        assertThat(notifyTasks.count()).isEqualTo(1);
        assertThat(notifyTasks.findByPaymentNo(paid.paymentNo()).orElseThrow().status())
                .isEqualTo(com.company.service.mall.domain.NotifyTask.Status.PENDING);
        assertThat(outbox.findAll()).extracting(message -> message.topic())
                .containsExactly("order.created", "order.paid");

        Payment replayed = orderService.pay(order.orderNo());

        assertThat(replayed.paymentNo()).isEqualTo(paid.paymentNo());
        assertThat(payments.count()).isEqualTo(1);
        assertThat(notifyTasks.count()).isEqualTo(1);
        assertThat(outbox.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("已支付订单不能取消，抛冲突而不是静默忽略")
    void cancelAfterPayRejected() {
        long productId = productService.create("苹果", 500L, 10).productId();
        Order order = orderService.place("req-4", 42L,
                List.of(new OrderService.LineRequest(productId, 1)));
        orderService.pay(order.orderNo());

        assertThatThrownBy(() -> orderService.cancel(order.orderNo(), "不想要了"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不可取消");
    }

    @Test
    @DisplayName("超时关单：CREATED 超过支付时限后置为 CLOSED 并归还库存")
    void closeExpiredOrders() throws InterruptedException {
        properties.getOrder().setPayTimeout(Duration.ZERO);
        long productId = productService.create("苹果", 500L, 5).productId();
        Order order = orderService.place("req-5", 42L,
                List.of(new OrderService.LineRequest(productId, 2)));
        Thread.sleep(5L);

        int closed = orderService.closeExpired();

        assertThat(closed).isEqualTo(1);
        assertThat(orderService.get(order.orderNo()).status()).isEqualTo(Order.Status.CLOSED);
        assertThat(stocks.findById(productId).orElseThrow().available()).isEqualTo(5);
        assertThat(stocks.findById(productId).orElseThrow().reserved()).isZero();
        assertThat(outbox.findAll()).extracting(message -> message.topic())
                .containsExactly("order.created", "order.closed");
    }

    @Test
    @DisplayName("并发同 requestId：只有一个线程真正建单，其余拿到同一张订单")
    void concurrentPlaceSameRequestId() throws Exception {
        long productId = productService.create("苹果", 500L, 10).productId();
        List<OrderService.LineRequest> lines = List.of(new OrderService.LineRequest(productId, 1));
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> orderNos = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        orderNos.add(orderService.place("req-concurrent", 42L, lines).orderNo());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        Set<String> distinct = orderNos.stream().collect(Collectors.toSet());
        assertThat(orderNos).hasSize(threads);
        assertThat(distinct).hasSize(1);
        assertThat(orders.count()).isEqualTo(1);
        assertThat(stocks.findById(productId).orElseThrow().available()).isEqualTo(9);
        assertThat(stocks.findById(productId).orElseThrow().reserved()).isEqualTo(1);
    }

    @Test
    @DisplayName("下单参数校验：空 requestId / 空行 / 重复商品都被拒绝")
    void placeValidation() {
        long productId = productService.create("苹果", 500L, 10).productId();

        assertThatThrownBy(() -> orderService.place(" ", 42L, List.of(new OrderService.LineRequest(productId, 1))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("requestId");
        assertThatThrownBy(() -> orderService.place("req-6", 42L, List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少包含一件商品");
        assertThatThrownBy(() -> orderService.place("req-7", 42L, List.of(
                new OrderService.LineRequest(productId, 1),
                new OrderService.LineRequest(productId, 2))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("合并");
        assertThat(orders.count()).isZero();
    }

    @Test
    @DisplayName("商品不存在时下单失败，且不产生订单")
    void placeWithUnknownProduct() {
        assertThatThrownBy(() -> orderService.place("req-8", 42L,
                List.of(new OrderService.LineRequest(999L, 1))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("商品不存在");
        assertThat(orders.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    @DisplayName("金额按分累计，不依赖客户端传值")
    void amountIsComputedFromServerSidePrices() {
        long apple = productService.create("苹果", 199L, 10).productId();
        long pear = productService.create("梨", 250L, 10).productId();

        Order order = orderService.place("req-9", 42L, List.of(
                new OrderService.LineRequest(apple, 2),
                new OrderService.LineRequest(pear, 3)));

        assertThat(order.amountCents()).isEqualTo(199L * 2 + 250L * 3);
    }

    @Test
    @DisplayName("库存与商品在同一建档方法里创建，避免有商品无库存")
    void productCreationSeedsStock() {
        Product product = productService.create("苹果", 500L, 10);

        assertThat(productService.stockOf(product.productId()).total()).isEqualTo(10);
        assertThat(productService.stockOf(product.productId()).available()).isEqualTo(10);
        assertThat(products.count()).isEqualTo(1);
        assertThat(stocks.count()).isEqualTo(1);
    }
}
