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
 * <p>【解决什么问题】一条下单链路要同时处理四类一致性风险：
 * <ul>
 *   <li><b>超卖</b>：并发下单必须靠 Try 预占库存来兜住</li>
 *   <li><b>重复提交</b>：同一个 requestId 只能生成一张订单</li>
 *   <li><b>状态交叉</b>：支付与取消/关单不能同时生效，否则钱和库存都对不上</li>
 *   <li><b>消息丢失</b>：建单/支付/取消事件必须和业务数据一起落地</li>
 * </ul>
 *
 * <p>【为什么这样设计】三把锁各管一件事，粒度不同：
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
 *
 * <p>为什么事件写本地消息表（outbox）而不是直接发 MQ：
 * 业务写库和发消息无法放进同一个原子操作，直接发一定会有"发出去了但库回滚"或
 * "库提交了但发送失败"两种情况。写进本地表与业务同事务，再由中继重试投递，
 * 才把漏发消除；代价是变成至少一次投递，消费端必须幂等。
 *
 * <p>【怎么读这段代码】下单主流程：加请求锁 → 幂等查旧单 → 查商品定价 → 建单落库
 * → TCC Try 预占库存 → 写 order.created 到 outbox。任何一步失败都会 {@link #abort}
 * 反向 Cancel。支付主流程：加订单锁 → TCC Confirm → 落支付流水 → 订单置 PAID
 * → 写 order.paid 到 outbox → 入队商户通知。
 * 每一步都有 info 日志，按 orderNo 串起来即可还原整条链路。
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
     *
     * <p>幂等性：幂等。幂等键是 requestId，第一次成功后所有重试都返回同一张订单；
     * 但失败（抛异常）时不会缓存失败结果，重试会重新执行。
     *
     * @param requestId 幂等键，不能为空且不超过 64 字符
     * @param userId    下单用户
     * @param lines     商品行，同一商品不允许重复出现
     * @return 新建或已存在的订单
     * @throws BizException 参数非法、加锁失败、商品不存在、库存不足
     */
    public Order place(String requestId, long userId, List<LineRequest> lines) {
        String normalizedRequestId = normalizeRequestId(requestId);
        List<LineRequest> validated = validateLines(lines);
        log.info("【下单】开始: requestId={}, userId={}, 行数={}", normalizedRequestId, userId, validated.size());
        LockHandle handle = lock.lock("order:req:" + normalizedRequestId,
                properties.getOrder().getLockWait(),
                properties.getOrder().getLockLease());
        if (handle == null) {
            throw new BizException(ErrorCode.CONFLICT, "同一请求正在处理中: " + normalizedRequestId);
        }
        try (LockHandle ignored = handle) {
            Optional<Order> existing = orders.findByRequestId(normalizedRequestId);
            if (existing.isPresent()) {
                // 幂等命中：直接把第一次的结果还回去，绝不再建一张单
                log.info("【下单】幂等命中，返回已有订单: requestId={}, orderNo={}",
                        normalizedRequestId, existing.get().orderNo());
                return existing.get();
            }
            List<OrderLine> orderLines = resolveLines(validated);
            Order order = new Order(nextOrderNo(), normalizedRequestId, userId, orderLines, Instant.now());
            // 先落单：TCC 分支 ID 需要稳定的订单号，空回滚/悬挂才有业务主键可查
            orders.save(order);
            log.info("【下单】阶段1/3 建单完成: orderNo={}, amount={}", order.orderNo(), order.amountCents());

            StockTccService.TryResult result;
            try {
                // 阶段2：Try 预占库存，失败即进入补偿
                result = stockTccService.tryReserve(order.orderNo(), orderLines);
            } catch (RuntimeException e) {
                log.warn("【下单】阶段2/3 库存预占异常，开始回滚: orderNo={}, error={}",
                        order.orderNo(), e.getMessage());
                abort(order, orderLines, "库存预占异常: " + e.getMessage());
                throw e;
            }
            if (!result.success()) {
                log.warn("【下单】阶段2/3 库存不足，开始回滚: orderNo={}, productId={}",
                        order.orderNo(), result.failedProductId());
                abort(order, orderLines, "库存不足: productId=" + result.failedProductId());
                throw new BizException(ErrorCode.CONFLICT,
                        "库存不足: productId=" + result.failedProductId());
            }
            log.info("【下单】阶段2/3 库存预占成功: orderNo={}", order.orderNo());
            // 阶段3：事件写本地消息表，与订单数据同源，后续由 outbox 中继投递
            enqueue("order.created", order.orderNo(), Map.of(
                    "orderNo", order.orderNo(),
                    "requestId", order.requestId(),
                    "userId", order.userId(),
                    "amountCents", order.amountCents()));
            log.info("【下单】阶段3/3 写入 order.created 事件完成: orderNo={}", order.orderNo());
            log.debug("下单成功: orderNo={}, amount={}", order.orderNo(), order.amountCents());
            return order;
        }
    }

    /**
     * 支付。
     *
     * <p>已支付订单重复支付时不做"直接返回"，而是把 Confirm、通知任务、支付事件
     * 全部再补齐一次：任何一步在崩溃中丢失，重放都能自愈。
     *
     * <p>幂等性：幂等。支付流水按 orderNo 唯一，Confirm 与通知入队本身也都幂等，
     * 因此重复调用只会补齐缺失的副作用，不会重复扣库存或重复生成流水。
     *
     * @param orderNo 订单号
     * @return 该订单的支付流水（新建或已存在）
     * @throws BizException         订单不存在、加锁失败、状态不可支付
     * @throws IllegalStateException 已支付但支付流水丢失（数据不一致）
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
            log.info("【支付】开始: orderNo={}, 当前状态={}, amount={}",
                    orderNo, order.status(), order.amountCents());
            if (order.status() == Order.Status.PAID) {
                Payment existing = payments.findByOrderNo(orderNo)
                        .orElseThrow(() -> new IllegalStateException("订单已支付但缺少支付流水: " + orderNo));
                // 已支付：重放 Confirm / 通知 / 事件，把可能丢失的副作用补齐
                log.info("【支付】订单已支付，执行幂等补齐: orderNo={}, paymentNo={}",
                        orderNo, existing.paymentNo());
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
            log.info("【支付】阶段1/4 库存 Confirm 完成: orderNo={}", orderNo);
            Payment payment = payments.findByOrderNo(orderNo).orElseGet(() -> {
                Payment created = new Payment("P" + orderNo, orderNo, order.userId(),
                        order.amountCents(), Instant.now());
                payments.save(created);
                return created;
            });
            log.info("【支付】阶段2/4 支付流水落地: orderNo={}, paymentNo={}", orderNo, payment.paymentNo());
            order.markPaid();
            orders.save(order);
            log.info("【支付】阶段3/4 订单置为 PAID: orderNo={}", orderNo);
            enqueuePaid(order, payment);
            // 通知是"最大努力"：失败不会回滚支付，只会在通知表里重试
            notifier.enqueue(payment);
            log.info("【支付】阶段4/4 支付事件与通知任务已落地: orderNo={}", orderNo);
            log.debug("支付成功: orderNo={}, paymentNo={}", orderNo, payment.paymentNo());
            return payment;
        }
    }

    /**
     * 主动取消：归还库存、把订单置为 CANCELLED。已取消时幂等返回。
     *
     * <p>幂等性：幂等。已 CANCELLED 直接返回；库存是否归还过由 TCC 分支状态保证。
     *
     * @param orderNo 订单号
     * @param reason  取消原因，空白时默认为"用户取消"
     * @return 取消后的订单
     * @throws BizException 订单不存在、加锁失败、状态不可取消
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
            log.info("【取消】开始: orderNo={}, 当前状态={}, reason={}",
                    orderNo, order.status(), normalizedReason);
            if (order.status() == Order.Status.CANCELLED) {
                log.info("【取消】幂等命中，订单已取消: orderNo={}", orderNo);
                return order;
            }
            if (order.status() != Order.Status.CREATED) {
                throw new BizException(ErrorCode.CONFLICT,
                        "订单 " + orderNo + " 当前状态 " + order.status() + "，不可取消");
            }
            // 取消先归还库存，再改订单状态：库存归还失败时订单仍是 CREATED，可以重试
            stockTccService.cancel(orderNo, order.lines());
            order.markCancelled(normalizedReason);
            orders.save(order);
            enqueue("order.cancelled", orderNo, Map.of("orderNo", orderNo, "reason", normalizedReason));
            log.info("【取消】完成: orderNo={}, 库存已归还, order.cancelled 已入 outbox", orderNo);
            return order;
        }
    }

    /**
     * 超时关单：CREATED 且超过 payTimeout 的订单，Cancel 归还库存后置为 CLOSED。
     *
     * <p>锁内必须二次检查状态 —— 扫描列表是快照，扫到支付成功之前的状态很正常。
     *
     * <p>幂等性：可重复调用。抢不到锁或状态已变化的订单会被跳过，下一轮再扫。
     *
     * @return 本轮成功关单的数量
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
                    // 快照之后被支付/取消了，锁内以最新状态为准
                    continue;
                }
                stockTccService.cancel(order.orderNo(), order.lines());
                order.markClosed("支付超时");
                orders.save(order);
                enqueue("order.closed", order.orderNo(),
                        Map.of("orderNo", order.orderNo(), "reason", "支付超时"));
                log.info("【超时关单】完成: orderNo={}, 库存已归还", order.orderNo());
                closed++;
            }
        }
        if (closed > 0) {
            log.info("【超时关单】本轮共关闭 {} 笔订单", closed);
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
        log.warn("【下单回滚】开始: orderNo={}, reason={}", order.orderNo(), reason);
        try {
            stockTccService.cancel(order.orderNo(), orderLines);
        } catch (RuntimeException e) {
            log.warn("TCC Cancel 失败，库存可能残留预占: orderNo={}", order.orderNo(), e);
        }
        order.markCancelled(reason);
        orders.save(order);
        enqueue("order.cancelled", order.orderNo(), Map.of("orderNo", order.orderNo(), "reason", reason));
        log.info("【下单回滚】完成: orderNo={}, 订单已取消, order.cancelled 已入 outbox", order.orderNo());
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
