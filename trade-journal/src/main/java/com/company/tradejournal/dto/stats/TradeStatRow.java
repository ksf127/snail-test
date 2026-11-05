package com.company.tradejournal.dto.stats;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 一组交易的统计结果（计划内或计划外）。
 *
 * <p>字段与 SQL 别名一一对应，由 {@code StatMapper#selectTradeStats} 直接映射；
 * {@code profitFactor} 不在 SQL 里算，因为它依赖 avgWin / avgLoss 两个聚合结果，
 * 放在 Java 里可以先判空再除，避免出现 null 或除零。
 */
@Data
public class TradeStatRow {

    /** true = 计划内，false = 计划外。 */
    private Boolean isPlanned;

    private Integer closedTrades;

    private Integer winTrades;

    /** 胜率，0~1，保留 4 位。 */
    private BigDecimal winRate;

    /** 平均盈利金额（只看盈利的交易）。 */
    private BigDecimal avgWin;

    /** 平均亏损金额（负数）。 */
    private BigDecimal avgLoss;

    /** 区间已实现盈亏合计。 */
    private BigDecimal totalPnl;

    /** 盈亏比 = 平均盈利 / 平均亏损绝对值，由 Service 计算。 */
    private BigDecimal profitFactor;
}
