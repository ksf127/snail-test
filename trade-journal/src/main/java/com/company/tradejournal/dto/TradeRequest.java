package com.company.tradejournal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 记录一笔成交。
 *
 * <p>【设计要点】{@code reason} 必填且不允许空白，这不是形式主义：
 * 复盘时判断「亏损是执行问题还是决策问题」全靠这一栏，允许留空就等于放弃了这个判断。
 *
 * @param tradeDate     成交日期
 * @param symbol        代码
 * @param name          名称
 * @param side          BUY / SELL
 * @param price         成交价
 * @param quantity      数量
 * @param fee           费用合计，允许为空（按 0 处理）
 * @param isPlanned     是否计划内，允许为空（按 true 处理，表外操作才需要显式标注）
 * @param planId        关联计划 ID，可为空
 * @param reason        买入理由，必须可证伪
 * @param sellCondition 买入时写下的卖出条件
 * @param accountId     所属账户，可为空（空按 1 处理，兼容改造前的单账户调用）
 */
public record TradeRequest(@NotNull LocalDate tradeDate,
                           @NotBlank String symbol,
                           @NotBlank String name,
                           @NotBlank @Pattern(regexp = "BUY|SELL", message = "side 只能是 BUY 或 SELL") String side,
                           @NotNull @DecimalMin(value = "0.001", message = "price 必须大于 0") BigDecimal price,
                           @NotNull @Min(value = 1, message = "quantity 必须大于 0") Integer quantity,
                           @DecimalMin(value = "0", message = "fee 不能为负") BigDecimal fee,
                           Boolean isPlanned,
                           Long planId,
                           @NotBlank String reason,
                           String sellCondition,
                           Long accountId) {
}
