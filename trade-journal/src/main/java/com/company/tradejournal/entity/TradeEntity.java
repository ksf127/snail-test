package com.company.tradejournal.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 成交流水：整套统计的唯一事实来源。
 *
 * <p>【解决什么问题】Excel 里最容易错的两件事是「盈亏怎么算」和「计划内外怎么区分」。
 * 这里把两者都固化成字段：{@code realizedPnl} 在卖出时由系统按加权成本算出，
 * {@code isPlanned} 在录入时确定，从此不需要人工维护任何汇总公式。
 *
 * <p>【为什么这样设计】只存流水、不存汇总。胜率、盈亏比、回撤全部从这张表实时算，
 * 避免出现「汇总表和流水对不上」这种最难排查的问题。
 *
 * <p>【怎么读这段代码】买入只有一条记录，{@code realizedPnl} 为 null；
 * 卖出时 {@code realizedPnl = 卖出金额 - 费用 - 加权成本 × 数量}。
 * 买入费用已经摊进成本价，所以不再重复扣减。
 */
@Data
@TableName("t_trade")
public class TradeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属账户，历史数据默认落在 1。 */
    private Long accountId;

    private LocalDate tradeDate;

    private String symbol;

    private String name;

    /** BUY / SELL，统一大写。 */
    private String side;

    private BigDecimal price;

    private Integer quantity;

    /** 佣金 + 印花税 + 过户费合计，由录入方填写。 */
    private BigDecimal fee;

    /** 仅卖出有值。 */
    private BigDecimal realizedPnl;

    /** 是否计划内交易。默认 true，因为表外操作才需要显式承认。 */
    private Boolean isPlanned;

    /** 关联的计划 ID，可为空。 */
    private Long planId;

    /** 买入理由，必须可证伪。 */
    private String reason;

    /** 买入时写下的卖出条件。 */
    private String sellCondition;

    private LocalDateTime createdAt;
}
