package com.company.tradejournal.service;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.company.tradejournal.dto.TradeRequest;
import com.company.tradejournal.entity.PositionEntity;
import com.company.tradejournal.entity.TradeEntity;
import com.company.tradejournal.mapper.PositionMapper;
import com.company.tradejournal.mapper.TradeMapper;
import com.company.tradejournal.mapper.TradePlanMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成交盈亏口径测试。
 *
 * <p>【测什么】买入费用是否摊进成本价、卖出时是否把两边费用都算进盈亏。
 * <p>【为什么必须测】这两个公式一旦写错，胜率和盈亏比会系统性偏移，
 * 而且偏移方向固定（通常是偏高），看报表完全看不出来。
 */
@ExtendWith(MockitoExtension.class)
class TradeServiceTest {

    @Mock
    private TradeMapper tradeMapper;

    @Mock
    private PositionMapper positionMapper;

    @Mock
    private TradePlanMapper planMapper;

    @InjectMocks
    private TradeService tradeService;

    @Test
    @DisplayName("首次买入：买入费用摊进加权成本")
    void buyShouldSpreadFeeIntoAvgCost() {
        when(positionMapper.selectPosition(1L, "600000")).thenReturn(null);

        TradeRequest request = new TradeRequest(LocalDate.of(2026, 11, 5), "600000", "浦发银行",
                "BUY", new BigDecimal("10.00"), 1000, new BigDecimal("5.00"),
                true, null, "估值处于近五年 20% 分位", null, null);

        TradeEntity trade = tradeService.record(request);

        ArgumentCaptor<PositionEntity> captor = ArgumentCaptor.forClass(PositionEntity.class);
        verify(positionMapper).insertPosition(captor.capture());
        // (10.00 × 1000 + 5.00) / 1000 = 10.0050
        assertEquals(0, new BigDecimal("10.0050").compareTo(captor.getValue().getAvgCost()));
        assertEquals(1000, captor.getValue().getQuantity());
        assertNull(trade.getRealizedPnl(), "买入没有已实现盈亏");
    }

    @Test
    @DisplayName("卖出：盈亏 = 卖出净额 - 加权成本 × 数量，持仓同步扣减")
    void sellShouldComputePnlWithFeeAndReducePosition() {
        PositionEntity position = new PositionEntity();
        position.setAccountId(1L);
        position.setSymbol("600000");
        position.setName("浦发银行");
        position.setQuantity(1000);
        position.setAvgCost(new BigDecimal("10.0050"));
        when(positionMapper.selectPosition(1L, "600000")).thenReturn(position);

        TradeRequest request = new TradeRequest(LocalDate.of(2026, 11, 6), "600000", "浦发银行",
                "SELL", new BigDecimal("11.00"), 400, new BigDecimal("6.00"),
                true, null, "到达止盈位", null, null);

        TradeEntity trade = tradeService.record(request);

        // 4400 - 6 - 10.0050 × 400 = 392.00
        assertEquals(0, new BigDecimal("392.00").compareTo(trade.getRealizedPnl()));
        assertEquals(600, position.getQuantity());
        verify(positionMapper).updatePosition(position);
        verify(positionMapper, never()).deletePosition(any(), any());
    }

    @Test
    @DisplayName("全部卖出：持仓清零时删除持仓行")
    void sellAllShouldDeletePosition() {
        PositionEntity position = new PositionEntity();
        position.setAccountId(1L);
        position.setSymbol("600000");
        position.setName("浦发银行");
        position.setQuantity(300);
        position.setAvgCost(new BigDecimal("10.0000"));
        when(positionMapper.selectPosition(1L, "600000")).thenReturn(position);

        TradeRequest request = new TradeRequest(LocalDate.of(2026, 11, 6), "600000", "浦发银行",
                "SELL", new BigDecimal("10.00"), 300, BigDecimal.ZERO,
                true, null, "逻辑不再成立", null, null);

        TradeEntity trade = tradeService.record(request);

        assertEquals(0, BigDecimal.ZERO.compareTo(trade.getRealizedPnl()));
        verify(positionMapper).deletePosition(1L, "600000");
        verify(positionMapper, never()).updatePosition(any(PositionEntity.class));
    }
}
