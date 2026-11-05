package com.company.tradejournal.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 当前持仓，由成交实时维护。
 *
 * <p>【解决什么问题】卖出时要算已实现盈亏，就必须知道加权成本。若每次都从流水重算 FIFO，
 * 代码复杂且容易和流水不一致；这里单独维护一份当前状态，卖出直接读。
 *
 * <p>【为什么这样设计】{@code avgCost} 采用加权平均成本，且<b>已包含买入费用</b>：
 * 买入时 {@code avgCost = (原成本金额 + 成交金额 + 费用) / 新数量}。
 * 这样卖出时只需 {@code 净卖出额 - avgCost × 数量}，不会漏算佣金。
 *
 * <p>【怎么读这段代码】主键是 {@code (accountId, symbol)} 复合键，所以不能再用 MyBatis-Plus 的
 * {@code selectById / updateById / deleteById}（它们只认单一主键），改由
 * {@code PositionMapper} 里的自定义 SQL 按账户 + 代码操作。
 * 数量减到 0 时直接删除该行，而不是留一条 quantity = 0 的记录，
 * 避免后续查询持仓时到处要加 {@code quantity > 0} 条件。
 */
@Data
@TableName("t_position")
public class PositionEntity {

    /** 所属账户，复合主键的第一列。 */
    private Long accountId;

    /** 代码，同一账户内唯一。 */
    private String symbol;

    private String name;

    private Integer quantity;

    /** 加权成本（含买入费用），保留 4 位小数。 */
    private BigDecimal avgCost;

    private LocalDateTime updatedAt;
}
