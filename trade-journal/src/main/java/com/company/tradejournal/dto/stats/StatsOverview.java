package com.company.tradejournal.dto.stats;

import java.time.LocalDate;
import java.util.List;

/**
 * 区间总览：一次请求拿到复盘最常看的三个指标。
 *
 * @param from            起始日期
 * @param to              结束日期
 * @param trades          计划内 / 计划外两组的胜率与盈亏比
 * @param drawdown        最大回撤
 * @param discipline      按月分组的纪律违背
 * @param totalViolations 区间内违背总次数
 */
public record StatsOverview(LocalDate from,
                            LocalDate to,
                            List<TradeStatRow> trades,
                            DrawdownStat drawdown,
                            List<MonthlyDisciplineStat> discipline,
                            int totalViolations) {
}
