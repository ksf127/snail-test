package com.company.tradejournal.dto.account;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 账户汇总：账户列表与新建账户都返回这个结构，前端只需要一套渲染逻辑。
 *
 * <p>【口径说明】
 * <ul>
 *   <li>equity / cash 取该账户最新一条每日净值；从未提交过复盘时都是 0。</li>
 *   <li>marketValue = equity − cash，不重新按持仓算，避免和净值口径打架。</li>
 *   <li>floatingPnl 用持仓与每只票最新快照收盘价估算，没有快照的持仓按 0 处理。</li>
 *   <li>returnPct 是百分数本身（5.20 表示 +5.20%），initialCapital 为 0 时为 null。</li>
 *   <li>winRate 与 PerformanceStat 同口径：0~1，保留 4 位，没有卖出记录时为 null。</li>
 * </ul>
 *
 * <p>金额字段统一保留 2 位小数，空值返回 0，不返回 null：
 * 前端做加减时最怕 null，表现为「—」或直接报错，而 0 在页面上就是「还没有数据」。
 *
 * @param id             账户 ID
 * @param name           账户名称
 * @param broker         券商
 * @param currency       币种
 * @param initialCapital 初始资金
 * @param equity         最新净值
 * @param cash           最新现金
 * @param marketValue    持仓市值 = 净值 − 现金
 * @param realizedPnl    已实现盈亏
 * @param floatingPnl    浮动盈亏
 * @param totalPnl       总盈亏 = 已实现 + 浮动
 * @param returnPct      收益率百分数
 * @param winRate        胜率 0~1
 * @param profitAmount   盈利合计
 * @param lossAmount     亏损合计（绝对值）
 * @param tradeCount     成交笔数
 * @param positionCount  持仓只数
 * @param snapshotDate   最新净值日期，没有净值时为 null
 */
public record AccountSummary(Long id,
                             String name,
                             String broker,
                             String currency,
                             BigDecimal initialCapital,
                             BigDecimal equity,
                             BigDecimal cash,
                             BigDecimal marketValue,
                             BigDecimal realizedPnl,
                             BigDecimal floatingPnl,
                             BigDecimal totalPnl,
                             BigDecimal returnPct,
                             BigDecimal winRate,
                             BigDecimal profitAmount,
                             BigDecimal lossAmount,
                             Integer tradeCount,
                             Integer positionCount,
                             LocalDate snapshotDate) {
}
