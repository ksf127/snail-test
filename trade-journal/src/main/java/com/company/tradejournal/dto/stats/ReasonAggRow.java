package com.company.tradejournal.dto.stats;

import java.math.BigDecimal;

import lombok.Data;

/**
 * 卖出原因归因的原始聚合行。
 *
 * <p>{@code reason} 已在 SQL 里 TRIM 并补齐「未记录原因」，
 * {@code winRate} 留到 Service 计算，保持和其他统计一致的四位小数口径。
 */
@Data
public class ReasonAggRow {

    private String reason;

    private Integer sellCount;

    private Integer winCount;

    private Integer lossCount;

    private BigDecimal realizedPnl;

    private BigDecimal avgPnl;
}
