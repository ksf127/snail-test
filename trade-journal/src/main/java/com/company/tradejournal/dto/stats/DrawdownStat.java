package com.company.tradejournal.dto.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 最大回撤结果。
 *
 * @param maxDrawdown  最大回撤比例，0~1，例如 0.0680 表示 6.8%
 * @param peakDate     回撤起点（区间内最高净值那天）
 * @param troughDate   回撤谷底（从峰值跌得最深那天）
 * @param peakEquity   峰值净值
 * @param troughEquity 谷底净值
 * @param samples      参与计算的净值点数，少于 2 时回撤无意义
 */
public record DrawdownStat(BigDecimal maxDrawdown,
                           LocalDate peakDate,
                           LocalDate troughDate,
                           BigDecimal peakEquity,
                           BigDecimal troughEquity,
                           int samples) {

    /** 净值点不足时返回的空结果，避免调用方判 null。 */
    public static DrawdownStat empty(int samples) {
        return new DrawdownStat(BigDecimal.ZERO, null, null, null, null, samples);
    }
}
