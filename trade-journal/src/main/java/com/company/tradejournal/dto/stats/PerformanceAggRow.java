package com.company.tradejournal.dto.stats;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 区间交易表现的原始聚合行。
 *
 * <p>SQL 只负责 SUM / AVG / MAX / MIN，胜率与盈亏比在 {@code StatService} 里算：
 * 这两处都要判空和除零，写在 SQL 里会变成多层 CASE，读起来比 Java 难得多。
 */
@Data
public class PerformanceAggRow {

    /** 区间成交总笔数（含买入）。 */
    private Integer tradeCount;

    /** 卖出且已实现盈亏的笔数。 */
    private Integer sellCount;

    private Integer winCount;

    private Integer lossCount;

    /** 盈利合计（正数）。 */
    private BigDecimal profitAmount;

    /** 亏损合计（负数，Service 会转成绝对值）。 */
    private BigDecimal lossAmount;

    private BigDecimal netRealized;

    private BigDecimal avgWin;

    /** 负数。 */
    private BigDecimal avgLoss;

    private BigDecimal maxWin;

    /** 负数。 */
    private BigDecimal maxLoss;
}
