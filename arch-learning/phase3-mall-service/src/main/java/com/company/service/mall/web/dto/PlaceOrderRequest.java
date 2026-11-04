package com.company.service.mall.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 下单入参。
 *
 * <p>【解决什么问题】下单接口必须能安全重试：网络超时、客户端重发、网关重放都会让同一请求到达多次，
 * 如果没有幂等键，用户就会看到两张订单、被扣两次库存。
 *
 * <p>【为什么这样设计】{@code requestId} 是幂等键：调用方重试必须复用同一个值，
 * 服务端据此保证"同一个请求只产生一张订单"。它由调用方生成而不是服务端生成，
 * 因为服务端生成的 ID 在"请求已处理但响应丢失"时无法被复用。
 * 商品行只传 productId 和数量，价格必须由服务端查表决定，防止请求被篡改后低价下单。
 *
 * <p>【怎么读这段代码】由 {@code MallController.placeOrder} 接收并校验，
 * 转成 {@code OrderService.LineRequest} 列表后交给 {@code OrderService.place}。
 *
 * @param requestId 幂等键，不能为空且不超过 64 字符
 * @param userId    下单用户，必须为正
 * @param lines     商品行，至少一条
 */
public record PlaceOrderRequest(
        @NotBlank(message = "不能为空（幂等键）")
        @Size(max = 64, message = "长度不能超过 64")
        String requestId,
        @Min(value = 1, message = "必须为正数")
        long userId,
        @NotEmpty(message = "至少包含一件商品")
        List<@Valid Line> lines) {

    public record Line(
            @Min(value = 1, message = "必须为正数")
            long productId,
            @Min(value = 1, message = "必须为正数")
            int quantity) {
    }
}
