package com.company.tradejournal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.tradejournal.entity.PositionDailyEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PositionDailyMapper extends BaseMapper<PositionDailyEntity> {

    /**
     * 按（账户、快照日期、代码）写入或覆盖一条快照。
     *
     * <p>【解决什么问题】导入当前持仓时，如果当天已经有复盘快照，直接 INSERT 会撞唯一键；
     * 先删后插又会让「导入一半失败」留下残缺的一天。
     *
     * <p>【为什么这样设计】和净值表一致，用唯一键 upsert，重复导入同一天只会覆盖，不会叠加。
     *
     * @param snapshot 快照
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO t_position_daily
                (account_id, snapshot_date, symbol, name, quantity, avg_cost, close_price,
                 position_ratio, logic_valid)
            VALUES
                (#{accountId}, #{snapshotDate}, #{symbol}, #{name}, #{quantity}, #{avgCost},
                 #{closePrice}, #{positionRatio}, #{logicValid})
            ON DUPLICATE KEY UPDATE
                name = VALUES(name), quantity = VALUES(quantity), avg_cost = VALUES(avg_cost),
                close_price = VALUES(close_price), position_ratio = VALUES(position_ratio),
                logic_valid = VALUES(logic_valid)
            """)
    int upsert(PositionDailyEntity snapshot);
}
