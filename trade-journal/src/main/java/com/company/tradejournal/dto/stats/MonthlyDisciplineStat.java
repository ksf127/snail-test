package com.company.tradejournal.dto.stats;

import java.util.List;

/**
 * 某个月的纪律违背情况。
 *
 * @param month 形如 2026-11
 * @param total 当月违背总次数
 * @param items 按次数从多到少排列的明细
 */
public record MonthlyDisciplineStat(String month, int total, List<ItemCount> items) {

    /**
     * 单一违背项的计数。
     *
     * @param itemCode  编码
     * @param itemLabel 中文名
     * @param count     次数
     */
    public record ItemCount(String itemCode, String itemLabel, int count) {
    }
}
