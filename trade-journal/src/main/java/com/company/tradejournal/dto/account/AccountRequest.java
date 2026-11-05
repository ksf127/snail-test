package com.company.tradejournal.dto.account;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 新建账户入参。
 *
 * <p>【设计要点】{@code initialCapital} 必须显式传入（可以为 0），因为它决定了收益率的固定分母；
 * 允许为空会让「收益率」变成没有基准的数字。{@code currency} 不填时按人民币处理，
 * 单机复盘场景下多币种只是备注用途，不做汇率换算。
 *
 * @param name           账户名称
 * @param broker         券商，可为空
 * @param currency       币种，为空按 CNY
 * @param initialCapital 初始资金，不能为负
 * @param note           备注，可为空
 */
public record AccountRequest(@NotBlank String name,
                             String broker,
                             String currency,
                             @NotNull @DecimalMin(value = "0", message = "initialCapital 不能为负") BigDecimal initialCapital,
                             String note) {
}
