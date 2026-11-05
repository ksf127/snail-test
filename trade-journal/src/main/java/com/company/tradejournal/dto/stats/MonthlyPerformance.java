package com.company.tradejournal.dto.stats;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 月度收益与环比、同比。
 *
 * <p>【解决什么问题】净值曲线上涨 5% 到底算好还是不好，要和自己的历史比：
 * 比上月快还是慢（环比）、比去年同月好还是差（同比）。
 *
 * <p>【口径】三个数都是百分数本身（2.31 = +2.31%），不是小数：
 * <ul>
 *   <li>月度收益 returnPct = 月末净值 / 上月末净值 − 1。
 *       首个有数据的月份没有上月，用当月第一个净值作基数，{@code partial=true}。</li>
 *   <li>环比 momPct = 本月收益率 − 上月收益率，单位是「百分点」，不是变化率。</li>
 *   <li>同比 yoyPct = 本月收益率 − 去年同月收益率，同样为百分点差；
 *       没有去年同月数据时为 null，不用 0 冒充。</li>
 * </ul>
 *
 * <p>【怎么读】{@code momPct} / {@code yoyPct} 为 null 表示样本不足，
 * 前端显示为「—」，不能当成 0 参与比较。
 */
@Data
public class MonthlyPerformance {

    private String month;

    private BigDecimal startEquity;

    private BigDecimal endEquity;

    private BigDecimal returnPct;

    private BigDecimal momPct;

    private BigDecimal yoyPct;

    private int tradeCount;

    private int violationCount;

    private boolean partial;
}
