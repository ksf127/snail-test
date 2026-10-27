package com.company.service.mall.domain;

/**
 * 订单行。
 *
 * <p>金额一律用 long 分：金额做浮点运算是财务系统事故的经典来源，
 * 用 BigDecimal 又要小心 {@code equals} 与 {@code compareTo} 不一致。
 * 分是能整除的最小货币单位，比较、累加、落库都不会产生精度问题。
 *
 * <p>单价在下单那一刻冻结进订单行，之后商品调价不影响历史订单。
 */
public record OrderLine(long productId, int quantity, long unitPriceCents) {

    public OrderLine {
        if (productId <= 0) {
            throw new IllegalArgumentException("productId 必须为正数: " + productId);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("购买数量必须为正数: " + quantity);
        }
        if (unitPriceCents < 0) {
            throw new IllegalArgumentException("单价不能为负: " + unitPriceCents);
        }
    }

    public long lineAmountCents() {
        return unitPriceCents * quantity;
    }
}
