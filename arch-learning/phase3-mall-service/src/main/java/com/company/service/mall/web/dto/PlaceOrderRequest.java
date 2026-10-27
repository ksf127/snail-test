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
 * <p>{@code requestId} 是幂等键：调用方重试必须复用同一个值，
 * 服务端据此保证"同一个请求只产生一张订单"。
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
