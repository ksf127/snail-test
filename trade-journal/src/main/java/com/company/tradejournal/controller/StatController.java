package com.company.tradejournal.controller;

import java.time.LocalDate;
import java.util.List;

import com.company.common.core.ApiResult;
import com.company.tradejournal.dto.stats.DrawdownStat;
import com.company.tradejournal.dto.stats.EquityPoint;
import com.company.tradejournal.dto.stats.MonthlyDisciplineStat;
import com.company.tradejournal.dto.stats.MonthlyPerformance;
import com.company.tradejournal.dto.stats.PerformanceStat;
import com.company.tradejournal.dto.stats.ReasonStat;
import com.company.tradejournal.dto.stats.StatsOverview;
import com.company.tradejournal.dto.stats.SymbolStat;
import com.company.tradejournal.dto.stats.TradeStatRow;
import com.company.tradejournal.service.StatService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统计接口：月度复盘真正要看的数字都在这里。
 *
 * <p>【怎么用】月底或每周末调一次 {@code /stats/overview} 即可；
 * 想单独看某一项时再调对应的细粒度接口，所有接口的区间口径完全一致。
 *
 * <p>【多账户】除纪律违背外，每个接口都支持可选的 {@code accountId}：
 * 不传表示「全部账户」（成交/持仓合并，净值按日期求和），传值只看单个账户。
 * 做成可选而不是必填，是为了让改造前的调用方和页面不加参数也能继续跑。
 */
@RestController
@RequestMapping("/stats")
public class StatController {

    private final StatService statService;

    public StatController(StatService statService) {
        this.statService = statService;
    }

    /**
     * 区间总览：胜率 / 盈亏比 + 最大回撤 + 纪律违背。
     *
     * @param from      起始日期，格式 yyyy-MM-dd
     * @param to        结束日期，格式 yyyy-MM-dd
     * @param accountId 账户 ID，为空表示全部账户
     * @return 总览
     */
    @GetMapping("/overview")
    public ApiResult<StatsOverview> overview(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        return ApiResult.ok(statService.overview(from, to, accountId));
    }

    /**
     * 计划内 / 计划外两组的胜率与盈亏比。
     *
     * @param from      起始日期，格式 yyyy-MM-dd
     * @param to        结束日期，格式 yyyy-MM-dd
     * @param accountId 账户 ID，为空表示全部账户
     * @return 两组统计
     */
    @GetMapping("/trades")
    public ApiResult<List<TradeStatRow>> trades(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        return ApiResult.ok(statService.tradeStats(from, to, accountId));
    }

    /**
     * 最大回撤（依赖每日净值序列）。
     *
     * @param from      起始日期，格式 yyyy-MM-dd
     * @param to        结束日期，格式 yyyy-MM-dd
     * @param accountId 账户 ID，为空表示全部账户
     * @return 回撤结果
     */
    @GetMapping("/drawdown")
    public ApiResult<DrawdownStat> drawdown(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        return ApiResult.ok(statService.drawdown(from, to, accountId));
    }

    /**
     * 净值序列，用于画净值曲线。
     *
     * @param from      起始日期，格式 yyyy-MM-dd
     * @param to        结束日期，格式 yyyy-MM-dd
     * @param accountId 账户 ID，为空表示全部账户
     * @return 按日期升序的净值点
     */
    @GetMapping("/equity")
    public ApiResult<List<EquityPoint>> equity(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        return ApiResult.ok(statService.equitySeries(from, to, accountId));
    }

    /**
     * 单标的区间表现，含收盘价走势序列。
     *
     * @param from      起始日期，格式 yyyy-MM-dd
     * @param to        结束日期，格式 yyyy-MM-dd
     * @param accountId 账户 ID，为空表示全部账户
     * @return 每个标的一行
     */
    @GetMapping("/symbols")
    public ApiResult<List<SymbolStat>> symbols(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        return ApiResult.ok(statService.symbolStats(from, to, accountId));
    }

    /**
     * 月度收益与环比、同比。
     *
     * @param from      起始日期，格式 yyyy-MM-dd
     * @param to        结束日期，格式 yyyy-MM-dd
     * @param accountId 账户 ID，为空表示全部账户
     * @return 按月份升序
     */
    @GetMapping("/monthly")
    public ApiResult<List<MonthlyPerformance>> monthly(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        return ApiResult.ok(statService.monthlyPerformance(from, to, accountId));
    }

    /**
     * 区间交易表现：胜率、盈亏金额、盈亏比与单笔极值。
     *
     * @param from      起始日期，格式 yyyy-MM-dd
     * @param to        结束日期，格式 yyyy-MM-dd
     * @param accountId 账户 ID，为空表示全部账户
     * @return 交易表现
     */
    @GetMapping("/performance")
    public ApiResult<PerformanceStat> performance(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        return ApiResult.ok(statService.performance(from, to, accountId));
    }

    /**
     * 卖出原因归因：哪类理由在赚钱、哪类在亏钱。
     *
     * @param from      起始日期，格式 yyyy-MM-dd
     * @param to        结束日期，格式 yyyy-MM-dd
     * @param accountId 账户 ID，为空表示全部账户
     * @param limit     返回条数，默认 10
     * @return 按卖出笔数降序
     */
    @GetMapping("/reasons")
    public ApiResult<List<ReasonStat>> reasons(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "accountId", required = false) Long accountId,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {
        return ApiResult.ok(statService.reasonStats(from, to, accountId, limit));
    }

    /**
     * 按月统计纪律违背。
     *
     * <p>不接收 accountId：纪律违背是「当天的人」的问题，与资金放在哪个账户无关。
     *
     * @param from 起始日期，格式 yyyy-MM-dd
     * @param to   结束日期，格式 yyyy-MM-dd
     * @return 月度统计
     */
    @GetMapping("/discipline")
    public ApiResult<List<MonthlyDisciplineStat>> discipline(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResult.ok(statService.discipline(from, to));
    }
}
