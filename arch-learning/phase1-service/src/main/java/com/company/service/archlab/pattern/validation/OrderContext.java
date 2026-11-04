package com.company.service.archlab.pattern.validation;

import java.math.BigDecimal;

/**
 * 下单校验上下文：责任链上每个节点只读自己关心的字段。
 *
 * <p>【解决什么问题】责任链的节点参数必须统一（否则接口就不是同一个接口了），
 * 但各节点需要的数据不同。把入参收成一个上下文对象，节点就能各取所需，
 * 而链路本身不需要知道每条规则依赖什么。
 *
 * <p>【为什么这样设计】用不可变 record 而不是可变 POJO：
 * 校验是只读过程，一旦允许节点修改上下文，后面的节点就会看到被前一个节点偷偷改过的数据，
 * 排查起来极其困难。代价是新增字段要改构造器，但这恰好能逼迫我们想清楚"真的需要这个字段吗"。
 *
 * <p>【怎么读这段代码】字段和 Controller 的入参一一对应；
 * 各节点用到的字段：UserActiveValidator → userId，QuantityValidator → quantity，
 * StockValidator → skuId。unitPrice/couponCode 目前只传不校验，留给后续扩展。
 *
 * @param userId     用户标识，blocked 前缀表示已封禁
 * @param skuId      商品标识，soldout 前缀表示无库存
 * @param quantity   购买数量
 * @param unitPrice  单价，当前链路未使用
 * @param couponCode 优惠券码，当前链路未使用
 */
public record OrderContext(String userId,
                           String skuId,
                           int quantity,
                           BigDecimal unitPrice,
                           String couponCode) {
}
