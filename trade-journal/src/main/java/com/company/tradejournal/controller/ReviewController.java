package com.company.tradejournal.controller;

import java.time.LocalDate;

import com.company.common.core.ApiResult;
import com.company.tradejournal.dto.ReviewRequest;
import com.company.tradejournal.entity.DailyReviewEntity;
import com.company.tradejournal.service.ReviewService;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每日复盘接口。
 *
 * <p>【怎么用】收盘后提交一次，内容对应 Markdown 模板的六块：
 * 大盘记录、强势/弱势方向、错过的机会、情绪、经验、明日待办，加上持仓收盘价与违背项。
 * 同一天重复提交视为修改，旧快照与旧违背明细会被覆盖，不会重复累计。
 */
@RestController
@RequestMapping("/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 保存某日复盘（新增或覆盖）。
     *
     * @param request 复盘内容
     * @return 保存后的复盘主记录
     */
    @PostMapping
    public ApiResult<DailyReviewEntity> save(@Valid @RequestBody ReviewRequest request) {
        return ApiResult.ok(reviewService.save(request));
    }

    /**
     * 查询某日复盘。
     *
     * @param date 复盘日期，格式 yyyy-MM-dd
     * @return 复盘记录，不存在时 data 为 null
     */
    @GetMapping("/{date}")
    public ApiResult<DailyReviewEntity> findByDate(
            @PathVariable("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(reviewService.findByDate(date));
    }
}
