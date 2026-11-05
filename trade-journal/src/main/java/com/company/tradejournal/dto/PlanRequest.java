package com.company.tradejournal.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 新建交易计划的入参。
 *
 * @param planDate    计划日期
 * @param symbol      代码
 * @param name        名称
 * @param triggerDesc 触发条件（若……）
 * @param actionDesc  触发动作（则……）
 * @param reason      依据，允许为空，但为空就失去了复盘的对照价值
 */
public record PlanRequest(@NotNull LocalDate planDate,
                          @NotBlank String symbol,
                          @NotBlank String name,
                          @NotBlank String triggerDesc,
                          @NotBlank String actionDesc,
                          String reason) {
}
