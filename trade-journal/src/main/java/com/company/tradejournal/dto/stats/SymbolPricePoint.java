package com.company.tradejournal.dto.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/** 单标的走势上的一个点。 */
@Data
public class SymbolPricePoint {

    private LocalDate tradeDate;

    private BigDecimal closePrice;
}
