package com.company.tradejournal.dto.stats;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

/**
 * 单个标的的区间表现：整体视图之外的「每个单项目」。
 *
 * <p>【解决什么问题】只看账户总盈亏，会把「一只票赚的钱」和「另一只票亏的钱」抵消掉，
 * 看不出钱到底是哪只票赚的、哪只票在拖后腿。
 *
 * <p>【口径】{@code realizedPnl} 只统计区间内卖出的已实现盈亏；
 * {@code priceChangePct} 用区间内首个与最后一个快照收盘价算，反映标的本身走势，
 * 与买卖时点无关——两者放在一起，才能区分「选股对了但卖早了」和「选股就错了」。
 *
 * <p>【怎么读】{@code series} 是画单标的曲线用的收盘价序列，按日期升序。
 */
@Data
public class SymbolStat {

    private String symbol;

    private String name;

    private int tradeCount;

    private int sellCount;

    private int winCount;

    private BigDecimal buyAmount;

    private BigDecimal sellAmount;

    private BigDecimal realizedPnl;

    private BigDecimal firstClose;

    private BigDecimal lastClose;

    /** 区间收盘价涨跌幅，百分数：2.31 表示 +2.31%。 */
    private BigDecimal priceChangePct;

    /** 当前持仓数量，0 表示已清仓。 */
    private int holdingQuantity;

    private List<SymbolPricePoint> series;
}
