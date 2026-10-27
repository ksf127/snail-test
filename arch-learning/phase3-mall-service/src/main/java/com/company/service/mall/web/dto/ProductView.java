package com.company.service.mall.web.dto;

import com.company.service.mall.domain.Product;
import com.company.service.mall.service.ProductService;

import java.time.Instant;

/**
 * 商品 + 库存快照。
 */
public record ProductView(long productId,
                          String name,
                          long priceCents,
                          Instant createdAt,
                          int stockTotal,
                          int stockAvailable,
                          int stockReserved) {

    public static ProductView from(Product product, ProductService.StockSnapshot stock) {
        return new ProductView(product.productId(), product.name(), product.priceCents(), product.createdAt(),
                stock.total(), stock.available(), stock.reserved());
    }
}
