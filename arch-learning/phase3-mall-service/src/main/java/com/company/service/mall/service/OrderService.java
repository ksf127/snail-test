package com.company.service.mall.service;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.service.mall.domain.Order;
import com.company.service.mall.domain.OrderLine;
import com.company.service.mall.domain.OutboxMessage;
import com.company.service.mall.domain.Payment;
import com.company.service.mall.domain.Product;
import com.company.service.mall.infra.OrderRepository;
import com.company.service.mall.infra.OutboxMessageRepository;
import com.company.service.mall.infra.PaymentRepository;
import com.company.service.mall.infra.ProductRepository;
import com.company.service.mall.support.MallProperties;
import com.company.starter.lock.DistributedLock;
import com.company.starter.lock.LockHandle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 下单 / 支付 / 取消 / 超时关单 —— 也就是 TCC 的协调器。
 *
 * <p>三把锁各管一件事，粒度不同：
 * <ul>
 *   <li>{@code order:req:{requestId}}：同一个请求号只处理一次，解决重复提交</li>
 *   <li>{@code order:state:{orderNo}}：同一订单的状态迁移串行化，避免"支付"与"取消"交叉</li>
 *   <li>{@code tcc:{orderNo}:{productId}}：TCC 分支内部互斥，见 {@link StockTccService}</li>
 * </ul>
 *
 * <p>为什么下单是"先建单再 Try"而不是"先 Try 再建单"：
 * 先有订单号，TCC 的分支 ID 才有稳定的业务主键（{@code orderNo:productId}），
 * 空回滚/悬挂的判断才能落地。代价是可能产生一张 CREATED 的废单，
 * 那就用同一个事务/补偿把它取消掉。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final AtomicLong orderSequence = new AtomicLong();

    private final AtomicLong messageSequence = new AtomicLong();

    private final OrderRepository orders;

    private final PaymentRepository payments;

    private final ProductRepository products;

    private final StockTccService stockTccService;

    private final OutboxMessageRepository outbox;

    private final PaymentNotifier notifier;

    private final DistributedLock lock;

    private final MallProperties properties;

    private final ObjectMapper objectMapper;

    public OrderService(OrderRepository orders,
                        PaymentRepository payments,
                        ProductRepository products,
                        StockTccService stockTccService,
                        OutboxMessageRepository outbox,
                        PaymentNotifier notifier,
                        DistributedLock lock,
                        MallProperties properties,
                        ObjectMapper objectMapper) {
        this.orders = orders;
        this.payments = payments;
        this.products = products;
        this.stockTccService = stockTccService;
        this.outbox = outbox;
        this.notifier = notifier;
        this.lock = lock;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 下单。
     *
     * <p>同一个 requestId 重复调用返回第一次创建的订单（包括库存不足被取消的那张），
     * 这样调用方重试时看到的永远是同一个结果，而不是"重试成功、原请求也成功"的两张单。
     */
    public Order place(String requestId, long userId, List<LineRequest> lines) {
        String normalizedRequestId = normalizeRequestId(requestId);
        List<LineRequest> validated = validateLines(lines);
        LockHandle handle = lock.lock("order:req:" + normalizedRequestId,
                properties.getOrder().getLockWait(),
                properties.getOrder().getLockLease());
        if (handle == null) {
            throw new BizException(ErrorCode.CONFLICT, "同一请求正在处理中: " + normalizedRequestId);
        }
        try (LockHandle ignored = handle) {
            Optional<Order> existing = orders.findByRequestId(normalizedRequestId);
            if (existing.isPresent()) {
                return existing.get();
            }
            List<OrderLine> orderLines = resolveLines(validated);
            Order order = new Order(nextOrderNo(), normalizedRequestId, userId, orderLines, Instant.now());
            // 先落单：TCC 分支 ID 需要稳定的订单号，空回滚/悬挂才有业务主键可查
            orders.save(order);

            StockTccService.TryResult result;
            try {
                result = stockTccService.tryReserve(order.orderNo(), orderLines);
            } catch (RuntimeException e) {
                abort(order, orderLines, "库存预占异常: " + e.getMessage());
                throw e;
            }
            if (!result.success()) {
                abort(order, orderLines, "库存不足: productId=" + result.failedProductId());
                throw new BizException(ErrorCode.CONFLICT,
                        "库存不足: productId=" + result.failedProductId());
            }
            enqueue("order.created", order.orderNo(), Map.of(
                    "orderNo", order.orderNo(),
                    "requestId", order.requestId(),
                    "userId", order.userId(),
                    "amountCents", order.amountCents()));
            log.debug("下单成功: orderNo={}, amount={}", order.orderNo(), order.amountCents());
            return order;
        }
    }

    /**
     * 支付。
     *
     * <p>已支付订单重复支付时不做"直接返回"，而是把 Confirm、通知任务、支付事件
     * 全部再补齐一次：任何一步在崩溃中丢失，重放都能自愈。
     */
    public Payment pay(String orderNo) {
        LockHandle handle = lock.lock("order:state:" + orderNo,
                properties.getOrder().getLockWait(),
                properties.getOrder().getLockLease());
        if (handle == null) {
            throw new BizException(ErrorCode.CONFLICT, "订单正在处理中: " + orderNo);
        }
        try (LockHandle ignored = handle) {
            Order order = requireOrder(orderNo);
            if (order.status() == Order.Status.PAID) {
                Payment existing = payments.findByOrderNo(orderNo)
                        .orElseThrow(() -> new IllegalStateException("订单已支付但缺少支付流水: " + orderNo));
                stockTccService.confirm(orderNo, order.lines());
                notifier.enqueue(existing);
                enqueuePaid(order, existing);
                return existing;
            }
            if (order.status() != Order.Status.CREATED) {
                throw new BizException(ErrorCode.CONFLICT,
                        "订单 " + orderNo + " 当前状态 " + order.status() + "，不可支付");
            }
            // 先 Confirm 再落支付流水：Confirm 幂等，崩溃后重试不会多扣
            stockTccService.confirm(orderNo, order.lines());
            Payment payment = payments.findByOrderNo(orderNo).orElseGet(() -> {
                Payment created = new Payment("P" + orderNo, orderNo, order.userId(),
                        order.amountCents(), Instant.now());
                payments.save(created);
                return created;
            });
            order.markPaid();
            orders.save(order);
            enqueuePaid(order, payment);
            notifier.enqueue(payment);
            log.debug("支付成功: orderNo={}, paymentNo={}", orderNo, payment.paymentNo());
            return payment;
        }
    }

    /**
     * 主动取消：归还库存、把订单置为 CANCELLED。已取消时幂等返回。
     */
    public Order cancel(String orderNo, String reason) {
        String normalizedReason = (reason == null || reason.isBlank()) ? "用户取消" : reason.trim();
        LockHandle handle = lock.lock("order:state:" + orderNo,
                properties.getOrder().getLockWait(),
                properties.getOrder().getLockLease());
        if (handle == null) {
            throw new BizException(ErrorCode.CONFLICT, "订单正在处理中: " + orderNo);
        }
        try (LockHandle ignored = handle) {
            Order order = requireOrder(orderNo);
            if (order.status() == Order.Status.CANCELLED) {
                return order;
            }
            if (order.status() != Order.Status.CREATED) {
                throw new BizException(ErrorCode.CONFLICT,
                        "订单 " + orderNo + " 当前状态 " + order.status() + "，不可取消");
            }
            stockTccService.cancel(orderNo, order.lines());
            order.markCancelled(normalizedReason);
            orders.save(order);
            enqueue("order.cancelled", orderNo, Map.of("orderNo", orderNo, "reason", normalizedReason));
            return order;
        }
    }

    /**
     * 超时关单：CREATED 且超过 payTimeout 的订单，Cancel 归还库存后置为 CLOSED。
     *
     * <p>锁内必须二次检查状态 —— 扫描列表是快照，扫到支付成功之前的状态很正常。
     */
    public int closeExpired() {
        Instant cutoff = Instant.now().minus(properties.getOrder().getPayTimeout());
        int closed = 0;
        for (Order order : orders.findAll()) {
            if (order.status() != Order.Status.CREATED || order.createdAt().isAfter(cutoff)) {
                continue;
            }
            LockHandle handle = lock.lock("order:state:" + order.orderNo(),
                    properties.getOrder().getLockWait(),
                    properties.getOrder().getLockLease());
            if (handle == null) {
                // 让别人先处理，下一轮再扫：绝不为了关单去抢锁
                continue;
            }
            try (LockHandle ignored = handle) {
                if (order.status() != Order.Status.CREATED) {
                    continue;
                }
                stockTccService.cancel(order.orderNo(), order.lines());
                order.markClosed("支付超时");
                orders.save(order);
                enqueue("order.closed", order.orderNo(),
                        Map.of("orderNo", order.orderNo(), "reason", "支付超时"));
                closed++;
            }
        }
        return closed;
    }

    public Order get(String orderNo) {
        return requireOrder(orderNo);
    }

    public Optional<Payment> paymentOf(String orderNo) {
        return payments.findByOrderNo(orderNo);
    }

    /**
     * 库存不足/预占异常时的回滚：Cancel 全部订单行（未 Try 的分支会走空回滚），
     * 再落取消事件。Cancel 失败只记日志，不能让原始异常被掩盖。
     */
    private void abort(Order order, List<OrderLine> orderLines, String reason) {
        try {
            stockTccService.cancel(order.orderNo(), orderLines);
        } catch (RuntimeException e) {
            log.warn("TCC Cancel 失败，库存可能残留预占: orderNo={}", order.orderNo(), e);
        }
        order.markCancelled(reason);
        orders.save(order);
        enqueue("order.cancelled", order.orderNo(), Map.of("orderNo", order.orderNo(), "reason", reason));
    }

    private Order requireOrder(String orderNo) {
        return orders.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "订单不存在: " + orderNo));
    }

    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "requestId 不能为空（幂等键）");
        }
        String trimmed = requestId.trim();
        if (trimmed.length() > 64) {
            throw new BizException(ErrorCode.PARAM_INVALID, "requestId 长度不能超过 64");
        }
        return trimmed;
    }

    private List<LineRequest> validateLines(List<LineRequest> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "订单至少包含一件商品");
        }
        Set<Long> productIds = new HashSet<>();
        List<LineRequest> validated = new ArrayList<>(lines.size());
        for (LineRequest line : lines) {
            if (line == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "订单行不能为空");
            }
            if (line.productId() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "productId 必须为正数: " + line.productId());
            }
            if (line.quantity() <= 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "购买数量必须为正数: " + line.quantity());
            }
            if (!productIds.add(line.productId())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "同一商品请合并为一行: " + line.productId());
            }
            validated.add(line);
        }
        return validated;
    }

    /**
     * 价格在服务端从商品表读取，绝不信任客户端传入的金额。
     */
    private List<OrderLine> resolveLines(List<LineRequest> lines) {
        List<OrderLine> resolved = new ArrayList<>(lines.size());
        for (LineRequest line : lines) {
            Product product = products.findById(line.productId())
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND,
                            "商品不存在: " + line.productId()));
            resolved.add(new OrderLine(product.productId(), line.quantity(), product.priceCents()));
        }
        return resolved;
    }

    private void enqueuePaid(Order order, Payment payment) {
        enqueue("order.paid", order.orderNo(), Map.of(
                "orderNo", order.orderNo(),
                "paymentNo", payment.paymentNo(),
                "amountCents", payment.amountCents()));
    }

    /**
     * 写本地消息表。dedupeKey = topic + 订单号，保证同一订单的同一类事件只有一条。
     *
     * <p>真实实现中这里与业务写库在同一个本地事务里，这一行 {@code outbox.save}
     * 必须和上面的 {@code orders.save} 一起提交或一起回滚。
     */
    private void enqueue(String topic, String orderNo, Object event) {
        String dedupeKey = topic + ":" + orderNo;
        if (outbox.findByDedupeKey(dedupeKey).isPresent()) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("事件序列化失败: topic=" + topic + ", orderNo=" + orderNo, e);
        }
        outbox.save(new OutboxMessage(messageSequence.incrementAndGet(), dedupeKey, topic, payload, Instant.now()));
    }

    /**
     * 订单号 = 毫秒时间戳 + 进程内自增。可读、可排序，且单进程内不会重复。
     * 多实例部署时要把自增换成号段或雪花算法 —— 与短链服务同理。
     */
    private String nextOrderNo() {
        return "O" + System.currentTimeMillis() + String.format("%05d", orderSequence.incrementAndGet() % 100000);
    }

    public record LineRequest(long productId, int quantity) {
    }
}
