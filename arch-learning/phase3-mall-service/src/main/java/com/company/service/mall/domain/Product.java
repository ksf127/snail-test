package com.company.service.mall.domain;

import java.time.Instant;

/**
 * 商品。
 *
 * <p>【解决什么问题】提供商品名称与当前售价，是下单时计算金额的唯一可信来源。
 * 客户端只传商品 ID 与数量，价格必须由服务端查表获得，否则请求被篡改就能低价下单。
 *
 * <p>【为什么这样设计】做成不可变 record：商品信息在下单链路里只读，
 * 不需要在对象上做状态迁移；真正的"库存"被拆到 {@link Stock} 单独管理，
 * 因为库存是高频并发写的热点，和低频变更的商详数据生命周期完全不同。
 * 金额统一用"分"存 long，避免浮点与 BigDecimal 在比较/序列化上的坑。
 *
 * <p>【怎么读这段代码】由 {@code ProductService.create} 建档，
 * 之后在下单时被 {@code OrderService.resolveLines} 读取，把单价冻结进 {@link OrderLine}。
 *
 * @param productId  商品 ID
 * @param name       商品名
 * @param priceCents 当前售价（分）
 * @param createdAt  建档时间
 */
public record Product(long productId, String name, long priceCents, Instant createdAt) {
}
