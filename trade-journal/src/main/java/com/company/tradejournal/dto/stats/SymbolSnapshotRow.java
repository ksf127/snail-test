package com.company.tradejournal.dto.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/** 某个标的某天的持仓快照收盘价，用于画单标的走势。 */
@Data
public class SymbolSnapshotRow {

    private LocalDate snapshotDate;

    private String symbol;

    private String name;

    private BigDecimal closePrice;
}
