package com.company.tradejournal.mapper;

import java.time.LocalDate;
import java.util.List;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.tradejournal.entity.TradeEntity;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TradeMapper extends BaseMapper<TradeEntity> {

    /**
     * 取区间内的成交流水，只查导入查重需要的列。
     *
     * <p>【解决什么问题】同一份券商流水被导入两次，重复的成交会把胜率、盈亏比全部算歪。
     * 查重需要「这个账户这段时间里已有哪些成交」，一次查回来在内存里比对，
     * 比每行一条 count 查询少几百次数据库往返。
     *
     * @param accountId 账户 ID
     * @param from      起始日期（含）
     * @param to        结束日期（含）
     * @return 只填了日期、代码、方向、价格、数量的成交
     */
    @Select("""
            SELECT trade_date, symbol, side, price, quantity
            FROM t_trade
            WHERE account_id = #{accountId} AND trade_date BETWEEN #{from} AND #{to}
            """)
    List<TradeEntity> selectForDedup(@Param("accountId") Long accountId,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);
}
