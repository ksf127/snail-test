package com.company.service.mall.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 建档商品。
 *
 * <p>【解决什么问题】把外部输入与领域对象隔开：客户端只能提交名称、价格、库存，
 * 无法指定 productId 或创建时间，避免外部篡改主键。
 *
 * <p>【为什么这样设计】用 record + Bean Validation 在入口处完成校验，
 * 非法请求在进入业务层之前就被拒绝，业务代码不必到处写 if。
 * 价格用 long 分而不是 double：金额是财务数据，浮点误差不可接受。
 *
 * <p>【怎么读这段代码】由 {@code MallController.createProduct} 接收，
 * 校验通过后转成 {@code ProductService.create} 的三个入参。
 *
 * @param name       商品名，不能为空且不超过 128 字符
 * @param priceCents 单价（分），用整数避免浮点误差
 * @param stock      初始库存，必须为正
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
