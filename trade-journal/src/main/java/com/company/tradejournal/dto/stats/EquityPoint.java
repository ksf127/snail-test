package com.company.tradejournal.dto.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/** 净值曲线上的一个点，只用于计算回撤。 */
@Data
public class EquityPoint {

    private LocalDate tradeDate;

    private BigDecimal equity;
}
