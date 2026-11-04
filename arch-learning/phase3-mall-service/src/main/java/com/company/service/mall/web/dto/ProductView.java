package com.company.service.mall.web.dto;

import com.company.service.mall.domain.Product;
import com.company.service.mall.service.ProductService;

import java.time.Instant;

/**
 * 商品 + 库存快照。
 *
 * <p>【解决什么问题】商品接口必须同时展示"卖多少钱"和"还剩多少"，否则用户下单后
 * 才发现库存不足。库存有三个数：总量、可用、冻结，缺一个都解释不清"为什么还能下单/不能下单"。
 *
 * <p>【为什么这样设计】只用快照而不是返回可变 {@code Stock} 对象：
 * 对外暴露可变库存会让调用方绕过 TCC 直接修改，也让 JSON 序列化带上实现细节。
 * {@code stockReserved} 单独给出，是为了让学习者直观看到"预占"确实存在，
 * 而不是被简单的"可用库存"掩盖。
 *
 * <p>【怎么读这段代码】{@link #from} 把商品与库存快照拼成扁平结构；
 * 由商品详情/列表接口调用，库存来自 {@code ProductService.stockOf}。
 *
 * @param productId      商品 ID
 * @param name           商品名
 * @param priceCents     售价（分）
 * @param createdAt      建档时间
 * @param stockTotal     库存总量
 * @param stockAvailable 当前可用库存
 * @param stockReserved  当前被 TCC 预占（冻结）的库存
 */
public record ProductView(long productId,
                          String name,
                          long priceCents,
                          Instant createdAt,
                          int stockTotal,
                          int stockAvailable,
                          int stockReserved) {

    /**
     * 商品与库存快照转视图。
     *
     * <p>幂等：纯转换，可重复调用。
     *
     * @param product 商品实体
     * @param stock   库存快照
     * @return 商品 + 库存的扁平视图
     */
    public static ProductView from(Product product, ProductService.StockSnapshot stock) {
        return new ProductView(product.productId(), product.name(), product.priceCents(), product.createdAt(),
                stock.total(), stock.available(), stock.reserved());
    }
}
