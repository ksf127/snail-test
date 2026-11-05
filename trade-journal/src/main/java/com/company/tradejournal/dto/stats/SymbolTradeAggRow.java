package com.company.tradejournal.dto.stats;

import java.math.BigDecimal;

import lombok.Data;

/** 按标的聚合的成交汇总，一笔标的一行。 */
@Data
public class SymbolTradeAggRow {

    private String symbol;

    private String name;

    private Integer tradeCount;

    private Integer sellCount;

    private Integer winCount;

    private BigDecimal buyAmount;

    private BigDecimal sellAmount;

    private BigDecimal realizedPnl;
}
