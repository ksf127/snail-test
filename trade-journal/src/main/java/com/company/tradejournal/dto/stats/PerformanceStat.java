package com.company.tradejournal.dto.stats;

import java.math.BigDecimal;

/**
 * 区间交易表现：只看卖出且已算出盈亏的记录，回答「这套做法挣的是不是正期望」。
 *
 * <p>【为什么和 TradeStatRow 分开】TradeStatRow 按计划内 / 计划外分组，是「对比」视角；
 * 这里不分组，是「整体」视角，并且补上了盈亏比需要的金额合计与极值。
 *
 * <p>【口径】
 * <ul>
 *   <li>tradeCount 是区间内全部成交笔数（含买入），sellCount 是有已实现盈亏的卖出笔数。</li>
 *   <li>winRate = winCount / sellCount，0~1 四位小数；没有卖出记录时为 null。</li>
 *   <li>lossAmount 取绝对值，avgLoss / maxLoss 保留负号，前端不必再判方向。</li>
 *   <li>profitFactor = 平均盈利 / 平均亏损绝对值，任一侧没有样本时为 null，
 *       不用 999 冒充：样本不足和盈亏比极高在图表上是两回事。</li>
 * </ul>
 *
 * @param tradeCount   区间成交总笔数
 * @param sellCount    卖出（已实现盈亏）笔数
 * @param winCount     盈利笔数
 * @param lossCount    亏损笔数
 * @param winRate      胜率 0~1
 * @param profitAmount 盈利合计
 * @param lossAmount   亏损合计（绝对值）
 * @param netRealized  已实现盈亏净额
 * @param avgWin       平均盈利
 * @param avgLoss      平均亏损（负数）
 * @param profitFactor 盈亏比
 * @param maxWin       最大单笔盈利
 * @param maxLoss      最大单笔亏损（负数）
 */
public record PerformanceStat(int tradeCount,
                              int sellCount,
                              int winCount,
                              int lossCount,
                              BigDecimal winRate,
                              BigDecimal profitAmount,
                              BigDecimal lossAmount,
                              BigDecimal netRealized,
                              BigDecimal avgWin,
                              BigDecimal avgLoss,
                              BigDecimal profitFactor,
                              BigDecimal maxWin,
                              BigDecimal maxLoss) {
}
