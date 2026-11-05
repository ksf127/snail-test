package com.company.tradejournal.controller;

import java.time.LocalDate;

import com.company.common.core.ApiResult;
import com.company.tradejournal.dto.PageResult;
import com.company.tradejournal.dto.PlanRequest;
import com.company.tradejournal.entity.TradePlanEntity;
import com.company.tradejournal.service.PlanService;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 盘前计划接口。
 *
 * <p>【怎么用】开盘前 {@code POST /plans} 写下「若 X 则 Y」，
 * 盘中触发时带着返回的 {@code id} 去 {@code POST /trades}，
 * 没触发就 {@code POST /plans/{id}/cancel}。
 */
@RestController
@RequestMapping("/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    /**
     * 新建交易计划。
     *
     * @param request 计划内容
     * @return 落库后的计划（含 id）
     */
    @PostMapping
    public ApiResult<TradePlanEntity> create(@Valid @RequestBody PlanRequest request) {
        return ApiResult.ok(planService.create(request));
    }

    /**
     * 分页查询计划，支持日期区间、状态与关键字。
     *
     * @param from    起始日期，格式 yyyy-MM-dd，可为空
     * @param to      结束日期，格式 yyyy-MM-dd，可为空
     * @param status  PENDING / EXECUTED / CANCELLED，可为空
     * @param keyword 代码或名称关键字，可为空
     * @param page    页码，从 1 开始
     * @param size    每页条数
     * @return 分页结果
     */
    @GetMapping
    public ApiResult<PageResult<TradePlanEntity>> list(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size) {
        return ApiResult.ok(planService.search(from, to, status, keyword, page, size));
    }

    /**
     * 取消计划（条件未触发、或临时决定不做）。
     *
     * @param id 计划 ID
     * @return 更新后的计划
     */
    @PostMapping("/{id}/cancel")
    public ApiResult<TradePlanEntity> cancel(@PathVariable("id") Long id) {
        return ApiResult.ok(planService.cancel(id));
    }
}
