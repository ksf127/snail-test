package com.company.service.mall.web.dto;

import com.company.service.mall.domain.Order;
import com.company.service.mall.domain.OrderLine;

import java.time.Instant;
import java.util.List;

/**
 * 订单视图。
 *
 * <p>【解决什么问题】把"写端状态"和"读端/投影状态"同时返回，让最终一致性可以被肉眼验证：
 * 下单后立刻查，可能看到 {@code status=PAID} 但 {@code projectedStatus=CREATED}，
 * 这就是 outbox 还没投递完的时间窗口。
 *
 * <p>【为什么这样设计】不把两个状态合并成一个"最新状态"：一旦合并就丢失了
 * "写端与读端是否收敛"的诊断信息，出问题时只能猜。保留两个字段是有意的，
 * 它不是 API 设计缺陷，而是可观测性的一部分。
 *
 * <p>【怎么读这段代码】{@code status} 来自订单库（写端），
 * {@code projectedStatus} 来自 {@link #from} 传入的事件投影结果（消费端），
 * {@code payment} 为 null 表示尚未支付。两者长期不一致才说明 outbox 或消费端有故障。
 */
public record OrderView(String orderNo,
                        String requestId,
                        long userId,
                        Order.Status status,
                        long amountCents,
                        List<Line> lines,
                        Instant createdAt,
                        Instant updatedAt,
                        String closeReason,
                        PaymentView payment,
                        Order.Status projectedStatus) {

    /**
     * 订单实体转视图。
     *
     * <p>幂等：纯转换，可重复调用。
     *
     * @param order           订单实体
     * @param payment         支付流水视图，未支付时为 null
     * @param projectedStatus 事件投影得到的订单状态，尚未消费到时为 null
     * @return 同时包含写端与投影端状态的视图
     */
    public static OrderView from(Order order, PaymentView payment, Order.Status projectedStatus) {
        return new OrderView(order.orderNo(), order.requestId(), order.userId(), order.status(),
                order.amountCents(), order.lines().stream().map(Line::from).toList(),
                order.createdAt(), order.updatedAt(), order.closeReason(), payment, projectedStatus);
    }

    public record Line(long productId, int quantity, long unitPriceCents, long lineAmountCents) {

        static Line from(OrderLine line) {
            return new Line(line.productId(), line.quantity(), line.unitPriceCents(), line.lineAmountCents());
        }
    }
}
