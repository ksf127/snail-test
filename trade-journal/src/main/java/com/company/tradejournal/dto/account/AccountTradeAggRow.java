package com.company.tradejournal.dto.account;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 账户成交汇总的原始行，只给 {@code AccountMapper} 映射用。
 *
 * <p>不直接把 SQL 结果映射成 {@link AccountSummary}：收益率、总盈亏这些字段要先做
 * 除零保护和小数位统一，放在 Service 里计算才不会被 SQL 的 null 语义带偏。
 */
@Data
public class AccountTradeAggRow {

    private Integer tradeCount;

    private Integer sellCount;

    private Integer winCount;

    /** 已实现盈亏合计（SELL 记录）。 */
    private BigDecimal realizedPnl;

    /** 盈利合计（正数）。 */
    private BigDecimal profitAmount;

    /** 亏损合计（绝对值）。 */
    private BigDecimal lossAmount;
}
