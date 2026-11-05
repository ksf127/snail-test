package com.company.tradejournal.mapper;

import java.math.BigDecimal;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.tradejournal.dto.account.AccountTradeAggRow;
import com.company.tradejournal.entity.AccountEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 账户表与账户维度的汇总查询。
 *
 * <p>【解决什么问题】账户列表要同时展示余额、已实现盈亏、浮动盈亏和胜率，
 * 这些数散在三张表里，逐个账户在 Java 里拼很容易把「哪个口径对应哪张表」搞混。
 *
 * <p>【为什么这样设计】聚合全部下推到 SQL，Java 只做除零保护和小数位统一。
 * 账户数量通常是个位数，按账户各查一次比写一个超大 JOIN 更好读、也更容易改。
 */
@Mapper
public interface AccountMapper extends BaseMapper<AccountEntity> {

    /**
     * 启用中的账户，按 id 升序。
     *
     * @return 账户列表
     */
    @Select("""
            SELECT id, name, broker, currency, initial_capital, note, active, created_at
            FROM t_account
            WHERE active = 1
            ORDER BY id
            """)
    List<AccountEntity> selectActive();

    /**
     * 账户的成交汇总：笔数、卖出笔数、胜负数、已实现盈亏与盈亏金额。
     *
     * @param accountId 账户 ID
     * @return 汇总行；账户没有任何成交时各金额为 0
     */
    @Select("""
            SELECT COUNT(*)                                                            AS tradeCount,
                   SUM(CASE WHEN side = 'SELL' AND realized_pnl IS NOT NULL THEN 1 ELSE 0 END) AS sellCount,
                   SUM(CASE WHEN side = 'SELL' AND realized_pnl > 0 THEN 1 ELSE 0 END)  AS winCount,
                   ROUND(SUM(CASE WHEN side = 'SELL' THEN COALESCE(realized_pnl, 0) ELSE 0 END), 2) AS realizedPnl,
                   ROUND(SUM(CASE WHEN side = 'SELL' AND realized_pnl > 0 THEN realized_pnl ELSE 0 END), 2) AS profitAmount,
                   ROUND(SUM(CASE WHEN side = 'SELL' AND realized_pnl < 0 THEN -realized_pnl ELSE 0 END), 2) AS lossAmount
            FROM t_trade
            WHERE account_id = #{accountId}
            """)
    AccountTradeAggRow selectTradeAgg(@Param("accountId") Long accountId);

    /**
     * 浮动盈亏：每只持仓用「最新一条快照收盘价 − 加权成本」× 数量。
     *
     * <p>没有快照的持仓不参与计算（等价于按 0 处理），因为缺收盘价时任何替代价格都是编的。
     *
     * @param accountId 账户 ID
     * @return 浮动盈亏合计，无持仓时返回 0
     */
    @Select("""
            SELECT COALESCE(SUM((d.close_price - p.avg_cost) * p.quantity), 0)
            FROM t_position p
            JOIN t_position_daily d
              ON d.account_id = p.account_id
             AND d.symbol = p.symbol
             AND d.snapshot_date = (
                    SELECT MAX(x.snapshot_date)
                    FROM t_position_daily x
                    WHERE x.account_id = p.account_id
                      AND x.symbol = p.symbol)
            WHERE p.account_id = #{accountId}
            """)
    BigDecimal selectFloatingPnl(@Param("accountId") Long accountId);

    /**
     * 账户当前持仓只数。
     *
     * @param accountId 账户 ID
     * @return 持仓行数
     */
    @Select("SELECT COUNT(*) FROM t_position WHERE account_id = #{accountId}")
    int selectPositionCount(@Param("accountId") Long accountId);
}
