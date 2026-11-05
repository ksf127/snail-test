package com.company.tradejournal.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.company.tradejournal.dto.stats.DrawdownStat;
import com.company.tradejournal.dto.stats.EquityPoint;
import com.company.tradejournal.dto.stats.TradeStatRow;
import com.company.tradejournal.mapper.StatMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * 统计计算测试。
 *
 * <p>【测什么】最大回撤的峰谷定位、盈亏比的除零与空值处理。
 * <p>【为什么必须测】回撤是「账户最难受的时候有多难受」，
 * 算法写错通常表现为回撤偏小，会让人误以为风险可控。
 */
@ExtendWith(MockitoExtension.class)
class StatServiceTest {

    @Mock
    private StatMapper statMapper;

    @InjectMocks
    private StatService statService;

    @Test
    @DisplayName("回撤：100 → 120 → 90 → 110，最大回撤 25%，峰谷日期正确")
    void drawdownShouldFindPeakAndTrough() {
        List<EquityPoint> series = List.of(
                point("2026-11-02", "100"),
                point("2026-11-03", "120"),
                point("2026-11-04", "90"),
                point("2026-11-05", "110"));

        DrawdownStat stat = StatService.computeDrawdown(series);

        // (120 - 90) / 120 = 0.25
        assertEquals(0, new BigDecimal("0.2500").compareTo(stat.maxDrawdown()));
        assertEquals(LocalDate.of(2026, 11, 3), stat.peakDate());
        assertEquals(LocalDate.of(2026, 11, 4), stat.troughDate());
        assertEquals(4, stat.samples());
    }

    @Test
    @DisplayName("回撤：净值单调上涨时为 0，只有一个点时返回空结果")
    void drawdownShouldHandleMonotonicAndEmptySeries() {
        DrawdownStat rising = StatService.computeDrawdown(List.of(
                point("2026-11-02", "100"),
                point("2026-11-03", "110")));
        assertEquals(0, BigDecimal.ZERO.compareTo(rising.maxDrawdown()));

        DrawdownStat single = StatService.computeDrawdown(List.of(point("2026-11-02", "100")));
        assertEquals(0, BigDecimal.ZERO.compareTo(single.maxDrawdown()));
        assertNull(single.peakDate());
        assertEquals(1, single.samples());
    }

    @Test
    @DisplayName("盈亏比：平均盈利 200 / 平均亏损 100 = 2.00；无亏损样本时返回 null")
    void profitFactorShouldGuardDivideByZero() {
        assertEquals(0, new BigDecimal("2.00")
                .compareTo(StatService.profitFactor(new BigDecimal("200"), new BigDecimal("-100"))));
        assertNull(StatService.profitFactor(new BigDecimal("200"), null));
        assertNull(StatService.profitFactor(null, new BigDecimal("-100")));
        assertNull(StatService.profitFactor(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    @DisplayName("成交统计：Mapper 返回的原始行会被补上盈亏比")
    void tradeStatsShouldFillProfitFactor() {
        TradeStatRow row = new TradeStatRow();
        row.setIsPlanned(true);
        row.setClosedTrades(10);
        row.setWinTrades(6);
        row.setWinRate(new BigDecimal("0.6"));
        row.setAvgWin(new BigDecimal("300"));
        row.setAvgLoss(new BigDecimal("-150"));
        row.setTotalPnl(new BigDecimal("1050"));
        when(statMapper.selectTradeStats(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null))
                .thenReturn(List.of(row));

        List<TradeStatRow> rows = statService.tradeStats(
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null);

        assertEquals(1, rows.size());
        assertEquals(0, new BigDecimal("2.00").compareTo(rows.get(0).getProfitFactor()));
    }

    private static EquityPoint point(String date, String equity) {
        EquityPoint point = new EquityPoint();
        point.setTradeDate(LocalDate.parse(date));
        point.setEquity(new BigDecimal(equity));
        return point;
    }
}
