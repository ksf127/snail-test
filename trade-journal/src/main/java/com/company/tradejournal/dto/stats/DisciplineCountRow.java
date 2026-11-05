package com.company.tradejournal.dto.stats;

import lombok.Data;

/** 违反纪律的计数行：某月某一种违背出现了几次。 */
@Data
public class DisciplineCountRow {

    /** 形如 2026-11。 */
    private String month;

    private String itemCode;

    private String itemLabel;

    private Integer cnt;
}
