package com.company.service.mall.domain;

/**
 * 订单行。
 *
 * <p>【解决什么问题】把"买了什么、买多少、按什么价格成交"固定下来。
 * 下单之后商品可能调价，如果订单行只存 productId 而在展示时回查商品表，
 * 历史订单的金额就会随调价漂移，对账必然对不上。
 *
 * <p>【为什么这样设计】金额一律用 long 分：金额做浮点运算是财务系统事故的经典来源，
 * 用 BigDecimal 又要小心 {@code equals} 与 {@code compareTo} 不一致。
 * 分是能整除的最小货币单位，比较、累加、落库都不会产生精度问题。
 * 单价在下单那一刻冻结进订单行，之后商品调价不影响历史订单。
 *
 * <p>【怎么读这段代码】构造时做一次参数校验（商品/数量/单价），
 * {@link #lineAmountCents()} 按"单价 × 数量"算出小计；订单总额由 {@link Order} 汇总所有行。
 *
 * @param productId      商品 ID，必须为正
 * @param quantity       购买数量，必须为正
 * @param unitPriceCents 成交单价（分），下单时冻结
 */
public record OrderLine(long productId, int quantity, long unitPriceCents) {

    /**
     * 紧凑构造器：所有校验都放在这里，保证对象一旦创建就是合法的。
     *
     * @throws IllegalArgumentException 商品 ID、数量或单价非法
     */
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

    /**
     * 行小计。
     *
     * <p>幂等性：纯函数，可重复调用。
     *
     * @return 单价 × 数量（分）
     */
    public long lineAmountCents() {
        return unitPriceCents * quantity;
    }
}
