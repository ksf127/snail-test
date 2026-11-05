package com.company.tradejournal.mapper;

import java.time.LocalDate;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.tradejournal.dto.stats.EquityPoint;
import com.company.tradejournal.entity.AccountDailyEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 每日净值读写。
 *
 * <p>【解决什么问题】{@code t_account_daily} 的主键从「交易日」改成「账户 + 交易日」后，
 * {@code selectById / updateById} 不再适用；而且复盘重复提交时必须覆盖而不是插入新行。
 *
 * <p>【为什么这样设计】写入统一用 {@code INSERT ... ON DUPLICATE KEY UPDATE}，
 * 把「查是否存在 → 再决定 insert/update」的两步合成一步，重复提交同一天不会留下两条净值，
 * 也避免了并发下的主键冲突。
 */
@Mapper
public interface AccountDailyMapper extends BaseMapper<AccountDailyEntity> {

    /**
     * 按账户 + 交易日取净值。
     *
     * @param accountId 账户 ID
     * @param tradeDate 交易日
     * @return 净值，不存在时返回 null
     */
    @Select("""
            SELECT account_id, trade_date, equity, cash, note
            FROM t_account_daily
            WHERE account_id = #{accountId} AND trade_date = #{tradeDate}
            """)
    AccountDailyEntity selectDaily(@Param("accountId") Long accountId,
                                   @Param("tradeDate") LocalDate tradeDate);

    /**
     * 取账户最新一条净值，用于「当前余额」。
     *
     * @param accountId 账户 ID
     * @return 最新净值，从未提交过复盘时返回 null
     */
    @Select("""
            SELECT account_id, trade_date, equity, cash, note
            FROM t_account_daily
            WHERE account_id = #{accountId}
            ORDER BY trade_date DESC
            LIMIT 1
            """)
    AccountDailyEntity selectLatest(@Param("accountId") Long accountId);

    /**
     * 按主键写入或覆盖净值。
     *
     * @param daily 净值
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO t_account_daily (account_id, trade_date, equity, cash, note)
            VALUES (#{accountId}, #{tradeDate}, #{equity}, #{cash}, #{note,jdbcType=VARCHAR})
            ON DUPLICATE KEY UPDATE equity = VALUES(equity), cash = VALUES(cash), note = VALUES(note)
            """)
    int upsert(AccountDailyEntity daily);

    /**
     * 某账户在区间内已有净值的日期，用于导入查重。
     *
     * @param accountId 账户 ID
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @return 已有净值的日期
     */
    @Select("""
            SELECT trade_date
            FROM t_account_daily
            WHERE account_id = #{accountId} AND trade_date BETWEEN #{from} AND #{to}
            """)
    List<LocalDate> selectDates(@Param("accountId") Long accountId,
                                @Param("from") LocalDate from,
                                @Param("to") LocalDate to);

    /**
     * 某账户的净值序列，供账户维度曲线使用。
     *
     * @param accountId 账户 ID
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @return 按日期升序
     */
    @Select("""
            SELECT trade_date AS tradeDate, equity
            FROM t_account_daily
            WHERE account_id = #{accountId} AND trade_date BETWEEN #{from} AND #{to}
            ORDER BY trade_date
            """)
    List<EquityPoint> selectSeries(@Param("accountId") Long accountId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to);
}
