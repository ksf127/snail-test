package com.company.tradejournal.controller;

import java.time.LocalDate;
import java.util.List;

import com.company.common.core.ApiResult;
import com.company.tradejournal.dto.account.AccountEquitySeries;
import com.company.tradejournal.dto.account.AccountRequest;
import com.company.tradejournal.dto.account.AccountSummary;
import com.company.tradejournal.service.AccountService;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账户接口。
 *
 * <p>【怎么用】页面顶部账户选择器调 {@code GET /accounts}；
 * 多账户净值对比调 {@code GET /accounts/equity}；新建账户用 {@code POST /accounts}，
 * 返回值就是完整的账户汇总，前端可以直接把新账户塞进列表而不必再请求一次。
 */
@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * 启用中的账户列表，含余额、盈亏与胜率。
     *
     * @return 按 id 升序
     */
    @GetMapping
    public ApiResult<List<AccountSummary>> list() {
        return ApiResult.ok(accountService.list());
    }

    /**
     * 新建账户。
     *
     * <p>若 initialCapital 大于 0，会写入当天的一条净值（equity = cash = initialCapital），
     * 让新账户的余额与收益率立即可见，不必等到当天复盘。
     *
     * @param request 新建参数
     * @return 新账户汇总
     */
    @PostMapping
    public ApiResult<AccountSummary> create(@Valid @RequestBody AccountRequest request) {
        return ApiResult.ok(accountService.create(request));
    }

    /**
     * 每个账户的净值序列，用于多账户对比曲线。
     *
     * @param from 起始日期，格式 yyyy-MM-dd
     * @param to   结束日期，格式 yyyy-MM-dd
     * @return 每个账户一条序列
     */
    @GetMapping("/equity")
    public ApiResult<List<AccountEquitySeries>> equity(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResult.ok(accountService.equitySeries(from, to));
    }
}
