package com.company.service.mall.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 建档商品。
 *
 * @param name       商品名
 * @param priceCents 单价（分），用整数避免浮点误差
 * @param stock      初始库存
 */
public record CreateProductRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 128, message = "长度不能超过 128")
        String name,
        @Min(value = 0, message = "不能为负")
        long priceCents,
        @Min(value = 1, message = "必须为正数")
        int stock) {
}
