package com.company.service.mall.domain;

import java.time.Instant;

/**
 * 商品。金额统一用"分"存 long，避免浮点与 BigDecimal 在比较/序列化上的坑。
 */
public record Product(long productId, String name, long priceCents, Instant createdAt) {
}
