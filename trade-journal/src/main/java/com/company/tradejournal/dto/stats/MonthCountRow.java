package com.company.tradejournal.dto.stats;

import lombok.Data;

/** 按月计数，用于月度表里的成交笔数。 */
@Data
public class MonthCountRow {

    private String month;

    private Integer cnt;
}
