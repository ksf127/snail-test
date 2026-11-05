package com.company.tradejournal.mapper;

import java.time.LocalDate;
import java.util.List;

import com.company.tradejournal.dto.stats.DisciplineCountRow;
import com.company.tradejournal.dto.stats.EquityPoint;
import com.company.tradejournal.dto.stats.MonthCountRow;
import com.company.tradejournal.dto.stats.PerformanceAggRow;
import com.company.tradejournal.dto.stats.ReasonAggRow;
import com.company.tradejournal.dto.stats.SymbolHoldingRow;
import com.company.tradejournal.dto.stats.SymbolSnapshotRow;
import com.company.tradejournal.dto.stats.SymbolTradeAggRow;
import com.company.tradejournal.dto.stats.TradeStatRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 统计专用 Mapper：所有聚合都在数据库里算，Java 只做「除零保护」和「序列扫描」。
 *
 * <p>【解决什么问题】把统计放在 SQL 里，一是避免把几十万条流水拉进内存，
 * 二是保证「同一天在不同页面看到的胜率是同一个数」——只有一处计算逻辑。
 *
 * <p>【为什么这样设计】只做能在 SQL 里确定完成的部分。最大回撤需要「逐行比较历史峰值」，
 * 用窗口函数写成一句也能跑，但可读性差且难以单测；这里改为只取净值序列，回撤在
 * {@code StatService} 里用纯 Java 扫描，可以直接写单元测试。
 *
 * <p>【多账户口径】{@code accountId} 为 null 表示「全部账户」：成交类指标跨账户合并，
 * 净值按日期 SUM 聚合；传了值就只算该账户。SQL 里统一写成
 * {@code (#{accountId,jdbcType=BIGINT} IS NULL OR account_id = ...)}，
 * 显式给出 jdbcType，是因为 MyBatis 在参数为 null 时无法推断类型，MySQL 会报
 * 「Invalid column type」之类的错误。
 *
 * <p>【怎么读这段代码】三句 SQL 分别对应三个指标：
 * 胜率与盈亏比看 {@link #selectTradeStats}，回撤看 {@link #selectEquitySeries}，
 * 纪律违背看 {@link #selectDisciplineCounts}。
 */
@Mapper
public interface StatMapper {

    /**
     * 按「是否计划内」统计已平仓交易的胜率、平均盈亏与总盈亏。
     *
     * <p>只统计 SELL 且 realized_pnl 非空的记录：买入没有已实现盈亏，混进来会把样本数算大。
     *
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @param accountId 账户 ID，为空表示全部账户
     * @return 计划内、计划外各一行（某组没有交易时不会出现该行）
     */
    @Select("""
            SELECT is_planned                                              AS isPlanned,
                   COUNT(*)                                                AS closedTrades,
                   SUM(CASE WHEN realized_pnl > 0 THEN 1 ELSE 0 END)       AS winTrades,
                   ROUND(SUM(CASE WHEN realized_pnl > 0 THEN 1 ELSE 0 END) / COUNT(*), 4) AS winRate,
                   ROUND(AVG(CASE WHEN realized_pnl > 0 THEN realized_pnl END), 2)        AS avgWin,
                   ROUND(AVG(CASE WHEN realized_pnl < 0 THEN realized_pnl END), 2)        AS avgLoss,
                   ROUND(SUM(realized_pnl), 2)                             AS totalPnl
            FROM t_trade
            WHERE side = 'SELL'
              AND realized_pnl IS NOT NULL
              AND trade_date BETWEEN #{from} AND #{to}
              AND (#{accountId,jdbcType=BIGINT} IS NULL OR account_id = #{accountId,jdbcType=BIGINT})
            GROUP BY is_planned
            """)
    List<TradeStatRow> selectTradeStats(@Param("from") LocalDate from,
                                        @Param("to") LocalDate to,
                                        @Param("accountId") Long accountId);

    /**
     * 取净值序列，按日期升序，用于计算最大回撤。
     *
     * <p>全部账户时按日期 SUM：每个账户当天各有一条净值，合并视图就是各账户之和。
     * 单账户时 SUM 也成立（一组一个值），所以不拆成两句 SQL。
     *
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @param accountId 账户 ID，为空表示全部账户
     * @return 每个交易日一个点
     */
    @Select("""
            SELECT trade_date AS tradeDate, SUM(equity) AS equity
            FROM t_account_daily
            WHERE trade_date BETWEEN #{from} AND #{to}
              AND (#{accountId,jdbcType=BIGINT} IS NULL OR account_id = #{accountId,jdbcType=BIGINT})
            GROUP BY trade_date
            ORDER BY trade_date
            """)
    List<EquityPoint> selectEquitySeries(@Param("from") LocalDate from,
                                         @Param("to") LocalDate to,
                                         @Param("accountId") Long accountId);

    /**
     * 按月、按违背项统计次数。
     *
     * <p>纪律违背属于「当天的人」，不属于某个券商账户，所以刻意不加 accountId 过滤。
     *
     * <p>用 DATE_FORMAT 而不是 YEAR()/MONTH() 两列：报表直接拿 month 分组展示，
     * 少一次字符串拼接，也避免前端自己格式化。
     *
     * @param from 起始日期（含）
     * @param to   结束日期（含）
     * @return 月份 × 违背项 的计数
     */
    @Select("""
            SELECT DATE_FORMAT(review_date, '%Y-%m') AS month,
                   item_code                         AS itemCode,
                   item_label                        AS itemLabel,
                   COUNT(*)                          AS cnt
            FROM t_discipline_violation
            WHERE review_date BETWEEN #{from} AND #{to}
            GROUP BY month, item_code, item_label
            ORDER BY month, cnt DESC
            """)
    List<DisciplineCountRow> selectDisciplineCounts(@Param("from") LocalDate from,
                                                    @Param("to") LocalDate to);

    /**
     * 按标的聚合区间成交。
     *
     * <p>买卖金额已经含费用方向：买入 = 金额 + 费用（成本），卖出 = 金额 - 费用（净回款）。
     * 这样「卖出回款 - 买入成本」与逐笔 realizedPnl 之和在口径上一致。
     *
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @param accountId 账户 ID，为空表示全部账户（同一标的跨账户合并成一行）
     * @return 每个标的一行，按已实现盈亏倒序
     */
    @Select("""
            SELECT symbol,
                   MAX(name)                                                   AS name,
                   COUNT(*)                                                    AS tradeCount,
                   SUM(CASE WHEN side = 'SELL' THEN 1 ELSE 0 END)              AS sellCount,
                   SUM(CASE WHEN side = 'SELL' AND realized_pnl > 0 THEN 1 ELSE 0 END) AS winCount,
                   ROUND(SUM(CASE WHEN side = 'BUY' THEN price * quantity + fee ELSE 0 END), 2) AS buyAmount,
                   ROUND(SUM(CASE WHEN side = 'SELL' THEN price * quantity - fee ELSE 0 END), 2) AS sellAmount,
                   ROUND(SUM(COALESCE(realized_pnl, 0)), 2)                     AS realizedPnl
            FROM t_trade
            WHERE trade_date BETWEEN #{from} AND #{to}
              AND (#{accountId,jdbcType=BIGINT} IS NULL OR account_id = #{accountId,jdbcType=BIGINT})
            GROUP BY symbol
            ORDER BY realizedPnl DESC, symbol
            """)
    List<SymbolTradeAggRow> selectSymbolTradeAgg(@Param("from") LocalDate from,
                                                 @Param("to") LocalDate to,
                                                 @Param("accountId") Long accountId);

    /**
     * 取区间内每个标的的每日收盘价快照。
     *
     * <p>只取有复盘的日子：没有复盘就没有可信收盘价，用买入价补点等于伪造走势。
     *
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @param accountId 账户 ID，为空表示全部账户
     * @return 按标的、日期升序
     */
    @Select("""
            SELECT snapshot_date AS snapshotDate, symbol, name, close_price AS closePrice
            FROM t_position_daily
            WHERE snapshot_date BETWEEN #{from} AND #{to}
              AND (#{accountId,jdbcType=BIGINT} IS NULL OR account_id = #{accountId,jdbcType=BIGINT})
            ORDER BY symbol, snapshot_date
            """)
    List<SymbolSnapshotRow> selectSymbolSnapshots(@Param("from") LocalDate from,
                                                  @Param("to") LocalDate to,
                                                  @Param("accountId") Long accountId);

    /**
     * 当前持仓数量，用于把「区间内没交易但仍在持仓」的标的补进单标的列表。
     *
     * <p>全部账户时按标的合并数量：同一个标的在两个账户都持有，列表上只应出现一行。
     *
     * @param accountId 账户 ID，为空表示全部账户
     * @return 每个持仓标的一行
     */
    @Select("""
            SELECT symbol, SUM(quantity) AS quantity
            FROM t_position
            WHERE (#{accountId,jdbcType=BIGINT} IS NULL OR account_id = #{accountId,jdbcType=BIGINT})
            GROUP BY symbol
            ORDER BY symbol
            """)
    List<SymbolHoldingRow> selectHoldings(@Param("accountId") Long accountId);

    /**
     * 按月统计成交笔数，供月度收益表对照。
     *
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @param accountId 账户 ID，为空表示全部账户
     * @return 月份升序
     */
    @Select("""
            SELECT DATE_FORMAT(trade_date, '%Y-%m') AS month, COUNT(*) AS cnt
            FROM t_trade
            WHERE trade_date BETWEEN #{from} AND #{to}
              AND (#{accountId,jdbcType=BIGINT} IS NULL OR account_id = #{accountId,jdbcType=BIGINT})
            GROUP BY month
            ORDER BY month
            """)
    List<MonthCountRow> selectTradeCountsByMonth(@Param("from") LocalDate from,
                                                 @Param("to") LocalDate to,
                                                 @Param("accountId") Long accountId);

    /**
     * 区间交易表现：一笔 SQL 拿到胜率、盈亏金额与极值所需的分子分母。
     *
     * <p>tradeCount 统计全部成交（买 + 卖），sellCount 只统计有已实现盈亏的卖出，
     * 两者相减就是「还没平仓的买入笔数」，能直接看出样本是不是偏少。
     *
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @param accountId 账户 ID，为空表示全部账户
     * @return 聚合行，区间内没有成交时各计数为 0、金额为 null
     */
    @Select("""
            SELECT COUNT(*)                                                              AS tradeCount,
                   SUM(CASE WHEN side = 'SELL' AND realized_pnl IS NOT NULL THEN 1 ELSE 0 END) AS sellCount,
                   SUM(CASE WHEN side = 'SELL' AND realized_pnl > 0 THEN 1 ELSE 0 END)   AS winCount,
                   SUM(CASE WHEN side = 'SELL' AND realized_pnl < 0 THEN 1 ELSE 0 END)   AS lossCount,
                   ROUND(SUM(CASE WHEN side = 'SELL' AND realized_pnl > 0 THEN realized_pnl ELSE 0 END), 2) AS profitAmount,
                   ROUND(SUM(CASE WHEN side = 'SELL' AND realized_pnl < 0 THEN realized_pnl ELSE 0 END), 2) AS lossAmount,
                   ROUND(SUM(CASE WHEN side = 'SELL' THEN COALESCE(realized_pnl, 0) ELSE 0 END), 2) AS netRealized,
                   ROUND(AVG(CASE WHEN side = 'SELL' AND realized_pnl > 0 THEN realized_pnl END), 2) AS avgWin,
                   ROUND(AVG(CASE WHEN side = 'SELL' AND realized_pnl < 0 THEN realized_pnl END), 2) AS avgLoss,
                   MAX(CASE WHEN side = 'SELL' AND realized_pnl > 0 THEN realized_pnl END) AS maxWin,
                   MIN(CASE WHEN side = 'SELL' AND realized_pnl < 0 THEN realized_pnl END) AS maxLoss
            FROM t_trade
            WHERE trade_date BETWEEN #{from} AND #{to}
              AND (#{accountId,jdbcType=BIGINT} IS NULL OR account_id = #{accountId,jdbcType=BIGINT})
            """)
    PerformanceAggRow selectPerformance(@Param("from") LocalDate from,
                                        @Param("to") LocalDate to,
                                        @Param("accountId") Long accountId);

    /**
     * 按卖出原因归因：哪个理由在赚钱、哪个理由在亏钱。
     *
     * <p>用表达式分组而不是直接 GROUP BY reason：reason 前后的空格会让同一类决策被拆成多行，
     * 空字符串则会出现一行「看不见原因」的数据，前端很难解释。
     *
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @param accountId 账户 ID，为空表示全部账户
     * @param limit     最多返回多少条
     * @return 按卖出笔数降序
     */
    @Select("""
            SELECT COALESCE(NULLIF(TRIM(reason), ''), '未记录原因')               AS reason,
                   COUNT(*)                                                      AS sellCount,
                   SUM(CASE WHEN realized_pnl > 0 THEN 1 ELSE 0 END)             AS winCount,
                   SUM(CASE WHEN realized_pnl < 0 THEN 1 ELSE 0 END)             AS lossCount,
                   ROUND(SUM(realized_pnl), 2)                                    AS realizedPnl,
                   ROUND(AVG(realized_pnl), 2)                                    AS avgPnl
            FROM t_trade
            WHERE side = 'SELL'
              AND realized_pnl IS NOT NULL
              AND trade_date BETWEEN #{from} AND #{to}
              AND (#{accountId,jdbcType=BIGINT} IS NULL OR account_id = #{accountId,jdbcType=BIGINT})
            GROUP BY COALESCE(NULLIF(TRIM(reason), ''), '未记录原因')
            ORDER BY sellCount DESC
            LIMIT #{limit}
            """)
    List<ReasonAggRow> selectReasonStats(@Param("from") LocalDate from,
                                         @Param("to") LocalDate to,
                                         @Param("accountId") Long accountId,
                                         @Param("limit") int limit);
}
