package com.company.tradejournal.dto.stats;

import java.math.BigDecimal;

/**
 * 按卖出原因归因的统计。
 *
 * <p>【解决什么问题】胜率只能说明「做得好不好」，不能说明「哪类决策在亏钱」。
 * 把 reason 分组后，常出现的「追高」「抄底」「补仓」会自然浮出来，
 * 这比任何主观总结都更能定位问题。
 *
 * <p>【口径】只统计卖出且 realized_pnl 非空的记录；reason 去掉首尾空白后分组，
 * 空字符串归为「未记录原因」，避免同一类决策被拆成十几行。
 *
 * @param reason      原因（已 TRIM，空值归为「未记录原因」）
 * @param sellCount   卖出笔数
 * @param winCount    盈利笔数
 * @param lossCount   亏损笔数
 * @param winRate     胜率 0~1
 * @param realizedPnl 已实现盈亏合计
 * @param avgPnl      平均每笔盈亏
 */
public record ReasonStat(String reason,
                         int sellCount,
                         int winCount,
                         int lossCount,
                         BigDecimal winRate,
                         BigDecimal realizedPnl,
                         BigDecimal avgPnl) {
}
