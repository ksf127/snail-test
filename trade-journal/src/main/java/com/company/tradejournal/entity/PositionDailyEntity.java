package com.company.tradejournal.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 每日持仓快照。
 *
 * <p>【解决什么问题】{@code t_position} 只有「现在」，没有「那天」。要复盘「11 月 6 日我为什么敢满仓」，
 * 必须能还原当时的数量、成本、仓位占比和止损位。
 *
 * <p>【为什么这样设计】{@code positionRatio} 在写入时就算好并存下来，而不是查询时再算：
 * 账户净值每天不同，用今天的净值去除历史市值，仓位占比会被算错。
 */
@Data
@TableName("t_position_daily")
public class PositionDailyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属账户，快照按账户隔离。 */
    private Long accountId;

    private LocalDate snapshotDate;

    private String symbol;

    private String name;

    private Integer quantity;

    private BigDecimal avgCost;

    private BigDecimal closePrice;

    /** 市值 / 当日账户净值。 */
    private BigDecimal positionRatio;

    private BigDecimal stopLoss;

    private BigDecimal takeProfit;

    /** 买入逻辑是否仍成立。 */
    private Boolean logicValid;
}
