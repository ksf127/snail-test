package com.company.service.mall.web.dto;

import com.company.service.mall.domain.Order;
import com.company.service.mall.domain.OrderLine;

import java.time.Instant;
import java.util.List;

/**
 * 订单视图。
 *
 * <p>{@code projectedStatus} 来自事件投影（消费端），{@code status} 来自订单库（写端）。
 * 两者短时间不一致是正常的 —— 这正是最终一致性的可观测窗口；
 * 长期不一致才说明 outbox 或消费端出了问题。
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
