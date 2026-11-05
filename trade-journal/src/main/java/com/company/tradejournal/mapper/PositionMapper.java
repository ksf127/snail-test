package com.company.tradejournal.mapper;

import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.tradejournal.entity.PositionEntity;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 当前持仓读写。
 *
 * <p>【解决什么问题】{@code t_position} 的主键是 {@code (account_id, symbol)}，
 * MyBatis-Plus 的 {@code selectById / updateById / deleteById} 只支持单一主键，
 * 用它们会按 symbol 误删其他账户的持仓，所以这里全部改成显式的复合键 SQL。
 *
 * <p>【为什么这样设计】自定义 SQL 把「账户 + 代码」两个条件写死，调用方不可能漏传其一；
 * 更新时顺带把 {@code updated_at} 刷成当前时间，排查「成本价什么时候变的」时有据可查。
 */
@Mapper
public interface PositionMapper extends BaseMapper<PositionEntity> {

    /**
     * 按账户 + 代码取单个持仓。
     *
     * @param accountId 账户 ID
     * @param symbol    代码
     * @return 持仓，不存在时返回 null
     */
    @Select("""
            SELECT account_id, symbol, name, quantity, avg_cost, updated_at
            FROM t_position
            WHERE account_id = #{accountId} AND symbol = #{symbol}
            """)
    PositionEntity selectPosition(@Param("accountId") Long accountId, @Param("symbol") String symbol);

    /**
     * 新增一行持仓。
     *
     * @param position 持仓
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO t_position (account_id, symbol, name, quantity, avg_cost)
            VALUES (#{accountId}, #{symbol}, #{name}, #{quantity}, #{avgCost})
            """)
    int insertPosition(PositionEntity position);

    /**
     * 更新持仓数量与成本。
     *
     * @param position 持仓
     * @return 影响行数
     */
    @Update("""
            UPDATE t_position
            SET name = #{name}, quantity = #{quantity}, avg_cost = #{avgCost}, updated_at = NOW()
            WHERE account_id = #{accountId} AND symbol = #{symbol}
            """)
    int updatePosition(PositionEntity position);

    /**
     * 清仓后删除持仓行。
     *
     * @param accountId 账户 ID
     * @param symbol    代码
     * @return 影响行数
     */
    @Delete("DELETE FROM t_position WHERE account_id = #{accountId} AND symbol = #{symbol}")
    int deletePosition(@Param("accountId") Long accountId, @Param("symbol") String symbol);

    /**
     * 账户下的持仓，按代码升序。
     *
     * @param accountId 账户 ID，为空表示全部账户
     * @return 持仓列表
     */
    @Select("""
            SELECT account_id, symbol, name, quantity, avg_cost, updated_at
            FROM t_position
            WHERE (#{accountId,jdbcType=BIGINT} IS NULL OR account_id = #{accountId,jdbcType=BIGINT})
            ORDER BY account_id, symbol
            """)
    List<PositionEntity> selectHoldings(@Param("accountId") Long accountId);
}
