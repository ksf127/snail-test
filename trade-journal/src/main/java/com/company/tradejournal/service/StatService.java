package com.company.tradejournal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.company.tradejournal.dto.stats.DisciplineCountRow;
import com.company.tradejournal.dto.stats.DrawdownStat;
import com.company.tradejournal.dto.stats.EquityPoint;
import com.company.tradejournal.dto.stats.MonthlyDisciplineStat;
import com.company.tradejournal.dto.stats.MonthlyPerformance;
import com.company.tradejournal.dto.stats.PerformanceAggRow;
import com.company.tradejournal.dto.stats.PerformanceStat;
import com.company.tradejournal.dto.stats.ReasonAggRow;
import com.company.tradejournal.dto.stats.ReasonStat;
import com.company.tradejournal.dto.stats.StatsOverview;
import com.company.tradejournal.dto.stats.SymbolHoldingRow;
import com.company.tradejournal.dto.stats.SymbolPricePoint;
import com.company.tradejournal.dto.stats.SymbolSnapshotRow;
import com.company.tradejournal.dto.stats.SymbolStat;
import com.company.tradejournal.dto.stats.SymbolTradeAggRow;
import com.company.tradejournal.dto.stats.TradeStatRow;
import com.company.tradejournal.mapper.StatMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 统计查询：胜率、盈亏比、最大回撤、纪律违背，全部从流水与净值序列现场推。
 *
 * <p>【解决什么问题】复盘模板只能看清一天，真正要看的规律都是跨周期的：
 * 计划内外的胜率差、最常犯的那条纪律、账户从峰值回撤了多少。
 * 这些结论手工算 Excel 很容易错，而且每个人的算法都不一样。
 *
 * <p>【为什么这样设计】聚合放 SQL，纯算术放 Java。
 * 聚合（COUNT/AVG/SUM）交给数据库，避免把流水拉进内存；
 * 盈亏比与回撤放在 Java，一是能对除零、空值做显式处理，二是可以直接单测，
 * 不必为了验证一个公式去起一个数据库。
 *
 * <p>【怎么读这段代码】{@link #tradeStats} 算胜率与盈亏比，
 * {@link #drawdown} 扫净值序列算最大回撤，{@link #discipline} 把违背明细聚合成月，
 * {@link #overview} 只是把三者拼起来，不含任何新逻辑。
 *
 * <p>【多账户口径】所有统计方法都多一个 {@code accountId}：传 null 表示「全部账户」，
 * 成交与持仓跨账户合并、净值按日期求和；传具体 ID 就只看该账户。
 * 纪律违背统计刻意不按账户拆分——它衡量的是一天里「人」的执行，而不是某个券商账户的表现。
 */
@Service
public class StatService {

    private static final Logger log = LoggerFactory.getLogger(StatService.class);

    private static final int RATIO_SCALE = 4;

    private static final int FACTOR_SCALE = 2;

    private static final int AMOUNT_SCALE = 2;

    private final StatMapper statMapper;

    public StatService(StatMapper statMapper) {
        this.statMapper = statMapper;
    }

    /**
     * 区间内计划内 / 计划外两组交易的胜率与盈亏比。
     *
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @param accountId 账户 ID，为空表示全部账户
     * @return 统计行，某组无交易时该组不出现
     */
    public List<TradeStatRow> tradeStats(LocalDate from, LocalDate to, Long accountId) {
        List<TradeStatRow> rows = statMapper.selectTradeStats(from, to, accountId);
        for (TradeStatRow row : rows) {
            row.setProfitFactor(profitFactor(row.getAvgWin(), row.getAvgLoss()));
        }
        log.info("【统计·交易】{} ~ {}｜{}｜{} 组样本：{}",
                from, to, accountLabel(accountId), rows.size(),
                rows.stream().map(r -> (Boolean.TRUE.equals(r.getIsPlanned()) ? "计划内" : "计划外")
                        + " " + r.getClosedTrades() + " 笔，胜率 " + r.getWinRate()).toList());
        return rows;
    }

    /**
     * 区间最大回撤。
     *
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @param accountId 账户 ID，为空表示全部账户
     * @return 回撤结果；净值点少于 2 个时返回空结果
     */
    public DrawdownStat drawdown(LocalDate from, LocalDate to, Long accountId) {
        List<EquityPoint> series = statMapper.selectEquitySeries(from, to, accountId);
        DrawdownStat stat = computeDrawdown(series);
        log.info("【统计·回撤】{} ~ {}｜{}｜样本 {} 天，最大回撤 {}",
                from, to, accountLabel(accountId), stat.samples(),
                stat.maxDrawdown().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%");
        return stat;
    }

    /**
     * 区间净值序列，供前端画曲线。
     *
     * <p>回撤接口只返回一个结论，画图需要整条序列，所以单独暴露。
     * 这里不做任何加工：曲线要展示的是原始净值，任何平滑或补点都会掩盖真实的波动。
     *
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @param accountId 账户 ID，为空表示全部账户（按日期 SUM 合并）
     * @return 按日期升序的净值点
     */
    public List<EquityPoint> equitySeries(LocalDate from, LocalDate to, Long accountId) {
        List<EquityPoint> series = statMapper.selectEquitySeries(from, to, accountId);
        log.info("【统计·净值】{} ~ {}｜{}｜{} 个点", from, to, accountLabel(accountId), series.size());
        return series;
    }

    /**
     * 区间内纪律违背，按月分组。
     *
     * @param from 起始日期（含）
     * @param to   结束日期（含）
     * @return 按月份升序的月度统计
     */
    public List<MonthlyDisciplineStat> discipline(LocalDate from, LocalDate to) {
        List<DisciplineCountRow> rows = statMapper.selectDisciplineCounts(from, to);
        // LinkedHashMap 保持 SQL 里的月份升序，不再排一次
        Map<String, List<DisciplineCountRow>> byMonth = new LinkedHashMap<>();
        for (DisciplineCountRow row : rows) {
            byMonth.computeIfAbsent(row.getMonth(), key -> new ArrayList<>()).add(row);
        }

        List<MonthlyDisciplineStat> result = new ArrayList<>(byMonth.size());
        byMonth.forEach((month, items) -> {
            List<MonthlyDisciplineStat.ItemCount> counts = items.stream()
                    .map(item -> new MonthlyDisciplineStat.ItemCount(
                            item.getItemCode(), item.getItemLabel(), item.getCnt()))
                    .toList();
            int total = counts.stream().mapToInt(MonthlyDisciplineStat.ItemCount::count).sum();
            result.add(new MonthlyDisciplineStat(month, total, counts));
        });
        log.info("【统计·纪律】{} ~ {}｜{} 个月，违背合计 {} 次", from, to, result.size(),
                result.stream().mapToInt(MonthlyDisciplineStat::total).sum());
        return result;
    }

    /**
     * 一次拿到复盘最常看的三个指标。
     *
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @param accountId 账户 ID，为空表示全部账户
     * @return 总览
     */
    public StatsOverview overview(LocalDate from, LocalDate to, Long accountId) {
        List<TradeStatRow> trades = tradeStats(from, to, accountId);
        DrawdownStat drawdown = drawdown(from, to, accountId);
        // 纪律违背不按账户过滤：它衡量的是一天里的执行，而不是钱放在哪个券商
        List<MonthlyDisciplineStat> discipline = discipline(from, to);
        int totalViolations = discipline.stream().mapToInt(MonthlyDisciplineStat::total).sum();
        return new StatsOverview(from, to, trades, drawdown, discipline, totalViolations);
    }

    /**
     * 单标的区间表现：账户整体之外，拆到每只票看钱是谁赚的。
     *
     * <p>标的来源取三处的并集：区间内有成交的、区间内有持仓快照的、当前仍持仓的。
     * 只取成交会漏掉「买了但一直没卖」的票，只取持仓会漏掉「区间内已清仓」的票。
     *
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @param accountId 账户 ID，为空表示全部账户（同一标的跨账户合并）
     * @return 每个标的一行，按已实现盈亏倒序
     */
    public List<SymbolStat> symbolStats(LocalDate from, LocalDate to, Long accountId) {
        List<SymbolTradeAggRow> aggregates = statMapper.selectSymbolTradeAgg(from, to, accountId);
        List<SymbolSnapshotRow> snapshots = statMapper.selectSymbolSnapshots(from, to, accountId);
        Map<String, Integer> holdings = statMapper.selectHoldings(accountId).stream()
                .collect(Collectors.toMap(SymbolHoldingRow::getSymbol,
                        row -> row.getQuantity() == null ? 0 : row.getQuantity(), (a, b) -> a));
        Map<String, List<SymbolSnapshotRow>> snapshotsBySymbol = snapshots.stream()
                .collect(Collectors.groupingBy(SymbolSnapshotRow::getSymbol, LinkedHashMap::new, Collectors.toList()));

        Map<String, SymbolStat> stats = new LinkedHashMap<>();
        for (SymbolTradeAggRow row : aggregates) {
            SymbolStat stat = new SymbolStat();
            stat.setSymbol(row.getSymbol());
            stat.setName(row.getName());
            stat.setTradeCount(row.getTradeCount() == null ? 0 : row.getTradeCount());
            stat.setSellCount(row.getSellCount() == null ? 0 : row.getSellCount());
            stat.setWinCount(row.getWinCount() == null ? 0 : row.getWinCount());
            stat.setBuyAmount(row.getBuyAmount() == null ? BigDecimal.ZERO : row.getBuyAmount());
            stat.setSellAmount(row.getSellAmount() == null ? BigDecimal.ZERO : row.getSellAmount());
            stat.setRealizedPnl(row.getRealizedPnl() == null ? BigDecimal.ZERO : row.getRealizedPnl());
            stats.put(row.getSymbol(), stat);
        }
        snapshotsBySymbol.forEach((symbol, rows) -> stats.computeIfAbsent(symbol, key -> {
            SymbolStat stat = new SymbolStat();
            stat.setSymbol(key);
            stat.setName(rows.get(0).getName());
            stat.setBuyAmount(BigDecimal.ZERO);
            stat.setSellAmount(BigDecimal.ZERO);
            stat.setRealizedPnl(BigDecimal.ZERO);
            return stat;
        }));

        stats.values().forEach(stat -> {
            List<SymbolSnapshotRow> rows = snapshotsBySymbol.getOrDefault(stat.getSymbol(), List.of());
            stat.setSeries(rows.stream()
                    .map(row -> {
                        SymbolPricePoint point = new SymbolPricePoint();
                        point.setTradeDate(row.getSnapshotDate());
                        point.setClosePrice(row.getClosePrice());
                        return point;
                    })
                    .toList());
            if (!rows.isEmpty()) {
                BigDecimal first = rows.get(0).getClosePrice();
                BigDecimal last = rows.get(rows.size() - 1).getClosePrice();
                stat.setFirstClose(first);
                stat.setLastClose(last);
                // 涨跌幅用百分数本身，和板块表的 pct_chg 保持同一口径，前端不用再乘 100
                stat.setPriceChangePct(first.signum() == 0 ? null
                        : last.subtract(first).divide(first, RATIO_SCALE, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(FACTOR_SCALE, RoundingMode.HALF_UP));
            }
            stat.setHoldingQuantity(holdings.getOrDefault(stat.getSymbol(), 0));
        });

        log.info("【统计·标的】{} ~ {}｜{}｜{} 只标的，盈亏合计 {}", from, to, accountLabel(accountId), stats.size(),
                stats.values().stream().map(SymbolStat::getRealizedPnl).reduce(BigDecimal.ZERO, BigDecimal::add));
        return new ArrayList<>(stats.values());
    }

    /**
     * 月度收益与环比、同比。
     *
     * <p>收益口径：月末净值 / 上月末净值 − 1。首个有数据的月份没有上月基数，
     * 用当月第一个净值作基数并标记 {@code partial=true}，避免把「月初到月末」当成完整月度收益。
     *
     * <p>环比与同比都是「两个收益率相减」的百分点差，不是变化率：
     * 本月 2%、上月 1%，环比是 +1 个百分点，不是 +100%。
     * 去年同月没有数据时返回 null，不用 0 冒充——0 会被读成「持平」。
     *
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @param accountId 账户 ID，为空表示全部账户
     * @return 按月份升序；没有净值数据时返回空列表
     */
    public List<MonthlyPerformance> monthlyPerformance(LocalDate from, LocalDate to, Long accountId) {
        List<EquityPoint> series = statMapper.selectEquitySeries(from, to, accountId);
        Map<String, Integer> tradeCounts = statMapper.selectTradeCountsByMonth(from, to, accountId).stream()
                .collect(Collectors.toMap(row -> row.getMonth(),
                        row -> row.getCnt() == null ? 0 : row.getCnt()));
        Map<String, Integer> violationCounts = new LinkedHashMap<>();
        for (MonthlyDisciplineStat item : discipline(from, to)) {
            violationCounts.put(item.month(), item.total());
        }

        Map<String, List<EquityPoint>> byMonth = new LinkedHashMap<>();
        for (EquityPoint point : series) {
            byMonth.computeIfAbsent(YearMonth.from(point.getTradeDate()).toString(), key -> new ArrayList<>())
                    .add(point);
        }

        List<MonthlyPerformance> result = new ArrayList<>(byMonth.size());
        Map<String, BigDecimal> returns = new LinkedHashMap<>();
        BigDecimal previousMonthEnd = null;
        for (Map.Entry<String, List<EquityPoint>> entry : byMonth.entrySet()) {
            List<EquityPoint> points = entry.getValue();
            boolean partial = previousMonthEnd == null;
            BigDecimal start = partial ? points.get(0).getEquity() : previousMonthEnd;
            BigDecimal end = points.get(points.size() - 1).getEquity();

            MonthlyPerformance row = new MonthlyPerformance();
            row.setMonth(entry.getKey());
            row.setStartEquity(start);
            row.setEndEquity(end);
            row.setPartial(partial);
            row.setTradeCount(tradeCounts.getOrDefault(entry.getKey(), 0));
            row.setViolationCount(violationCounts.getOrDefault(entry.getKey(), 0));
            row.setReturnPct(start.signum() == 0 ? null
                    : end.subtract(start).divide(start, RATIO_SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).setScale(FACTOR_SCALE, RoundingMode.HALF_UP));

            returns.put(entry.getKey(), row.getReturnPct());
            result.add(row);
            previousMonthEnd = end;
        }

        for (int i = 0; i < result.size(); i++) {
            MonthlyPerformance row = result.get(i);
            if (i > 0 && row.getReturnPct() != null && result.get(i - 1).getReturnPct() != null) {
                row.setMomPct(row.getReturnPct().subtract(result.get(i - 1).getReturnPct()));
            }
            BigDecimal lastYear = returns.get(YearMonth.parse(row.getMonth()).minusYears(1).toString());
            if (row.getReturnPct() != null && lastYear != null) {
                row.setYoyPct(row.getReturnPct().subtract(lastYear));
            }
        }

        log.info("【统计·月度】{} ~ {}｜{}｜{} 个月：{}", from, to, accountLabel(accountId), result.size(),
                result.stream().map(row -> row.getMonth() + " " + row.getReturnPct() + "%").toList());
        return result;
    }

    /**
     * 区间交易表现：胜率、盈亏金额、盈亏比与单笔极值。
     *
     * <p>样本口径是「卖出且已实现盈亏非空」的记录：买入没有兑现，不能算胜负。
     * 胜率与盈亏比在这里统一补算，避免前端拿到两个口径不同的数。
     *
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @param accountId 账户 ID，为空表示全部账户
     * @return 交易表现；区间内没有成交时各计数为 0、金额为 0，胜率为 null
     */
    public PerformanceStat performance(LocalDate from, LocalDate to, Long accountId) {
        PerformanceAggRow row = statMapper.selectPerformance(from, to, accountId);
        int tradeCount = intValue(row == null ? null : row.getTradeCount());
        int sellCount = intValue(row == null ? null : row.getSellCount());
        int winCount = intValue(row == null ? null : row.getWinCount());
        int lossCount = intValue(row == null ? null : row.getLossCount());

        BigDecimal winRate = sellCount == 0 ? null
                : BigDecimal.valueOf(winCount)
                .divide(BigDecimal.valueOf(sellCount), RATIO_SCALE, RoundingMode.HALF_UP);
        BigDecimal lossAmount = row == null || row.getLossAmount() == null
                ? money(BigDecimal.ZERO)
                : money(row.getLossAmount().abs());
        BigDecimal avgLoss = row == null ? money(BigDecimal.ZERO) : money(row.getAvgLoss());
        BigDecimal maxLoss = row == null ? money(BigDecimal.ZERO) : money(row.getMaxLoss());

        PerformanceStat stat = new PerformanceStat(
                tradeCount,
                sellCount,
                winCount,
                lossCount,
                winRate,
                row == null ? money(BigDecimal.ZERO) : money(row.getProfitAmount()),
                lossAmount,
                row == null ? money(BigDecimal.ZERO) : money(row.getNetRealized()),
                row == null ? money(BigDecimal.ZERO) : money(row.getAvgWin()),
                avgLoss,
                profitFactor(row == null ? null : row.getAvgWin(), row == null ? null : row.getAvgLoss()),
                row == null ? money(BigDecimal.ZERO) : money(row.getMaxWin()),
                maxLoss);

        log.info("【统计·表现】{} ~ {}｜{}｜成交 {} 笔（卖出 {}），胜率 {}，净已实现 {}，盈亏比 {}",
                from, to, accountLabel(accountId), tradeCount, sellCount, winRate,
                stat.netRealized(), stat.profitFactor());
        return stat;
    }

    /**
     * 卖出原因归因，按卖出笔数降序取前 N 条。
     *
     * <p>{@code limit} 做上下限钳制：前端传 0 或负数会让 SQL 直接报错，
     * 传太大又会把「只出现过一次」的零散原因铺满页面，反而看不出主要矛盾。
     *
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @param accountId 账户 ID，为空表示全部账户
     * @param limit     返回条数，钳制到 1~100
     * @return 原因统计列表
     */
    public List<ReasonStat> reasonStats(LocalDate from, LocalDate to, Long accountId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<ReasonAggRow> rows = statMapper.selectReasonStats(from, to, accountId, safeLimit);

        List<ReasonStat> result = new ArrayList<>(rows.size());
        for (ReasonAggRow row : rows) {
            int sellCount = intValue(row.getSellCount());
            int winCount = intValue(row.getWinCount());
            BigDecimal winRate = sellCount == 0 ? null
                    : BigDecimal.valueOf(winCount)
                    .divide(BigDecimal.valueOf(sellCount), RATIO_SCALE, RoundingMode.HALF_UP);
            result.add(new ReasonStat(
                    row.getReason(),
                    sellCount,
                    winCount,
                    intValue(row.getLossCount()),
                    winRate,
                    money(row.getRealizedPnl()),
                    money(row.getAvgPnl())));
        }
        log.info("【统计·原因】{} ~ {}｜{}｜{} 类原因，卖出合计 {}", from, to, accountLabel(accountId), result.size(),
                result.stream().mapToInt(ReasonStat::sellCount).sum());
        return result;
    }

    /**
     * 日志里的账户描述：null 表示合并视图，不要打印成「null」让人误以为是脏数据。
     */
    private static String accountLabel(Long accountId) {
        return accountId == null ? "全部账户" : "账户 " + accountId;
    }

    private static int intValue(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 金额统一两位小数，null 归零：前端做加减时不用再判空。
     */
    private static BigDecimal money(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
                : value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 盈亏比 = 平均盈利 / 平均亏损绝对值。
     *
     * <p>没有亏损交易时返回 null 而不是 999：这个数在数学上不存在，
     * 给出一个大数会让人误以为盈亏比极高、策略很好，实际上只是样本还不够。
     *
     * @param avgWin  平均盈利，可为空（该组无盈利交易）
     * @param avgLoss 平均亏损（负数），可为空（该组无亏损交易）
     * @return 盈亏比，无法计算时返回 null
     */
    static BigDecimal profitFactor(BigDecimal avgWin, BigDecimal avgLoss) {
        if (avgWin == null || avgLoss == null || avgLoss.signum() == 0) {
            return null;
        }
        return avgWin.divide(avgLoss.abs(), FACTOR_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 扫描净值序列求最大回撤。
     *
     * <p>算法只有一行核心逻辑：维护「到当前为止的最高净值」，
     * 每次用 {@code (峰值 - 当前) / 峰值} 更新最大回撤，并记住峰值日与谷底日。
     * 单独抽成静态方法是为了能脱离数据库直接单测。
     *
     * @param series 按日期升序的净值点，可为空
     * @return 回撤结果；点数少于 2 时返回空结果
     */
    static DrawdownStat computeDrawdown(List<EquityPoint> series) {
        if (series == null || series.size() < 2) {
            return DrawdownStat.empty(series == null ? 0 : series.size());
        }

        EquityPoint peak = series.get(0);
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        EquityPoint maxPeak = peak;
        EquityPoint maxTrough = peak;

        for (EquityPoint point : series) {
            if (point.getEquity().compareTo(peak.getEquity()) > 0) {
                peak = point;
                continue;
            }
            // 峰值当天自己不算回撤，所以只在跌破峰值时计算
            BigDecimal drawdown = peak.getEquity().subtract(point.getEquity())
                    .divide(peak.getEquity(), RATIO_SCALE, RoundingMode.HALF_UP);
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
                maxPeak = peak;
                maxTrough = point;
            }
        }

        return new DrawdownStat(maxDrawdown, maxPeak.getTradeDate(), maxTrough.getTradeDate(),
                maxPeak.getEquity(), maxTrough.getEquity(), series.size());
    }
}
