package com.company.tradejournal.dto.stats;

import lombok.Data;

/** 当前持仓数量，仅用于把持仓补进单标的列表。 */
@Data
public class SymbolHoldingRow {

    private String symbol;

    private Integer quantity;
}
