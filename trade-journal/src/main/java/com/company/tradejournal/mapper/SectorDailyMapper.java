package com.company.tradejournal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.tradejournal.entity.SectorDailyEntity;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface SectorDailyMapper extends BaseMapper<SectorDailyEntity> {

    /**
     * 按「板块 + 交易日」幂等写入，重复导入同一天只更新数值，不产生重复行。
     *
     * <p>用 ON DUPLICATE KEY UPDATE 而不是先查后写：导入是可重跑的批处理，
     * 单条 upsert 天然幂等，中断后直接重跑即可。
     *
     * @param row 行情行
     * @return 影响行数（插入 1，更新 2）
     */
    @Insert("""
            INSERT INTO t_sector_daily (trade_date, sector_code, sector_name, close_value, pct_chg, note)
            VALUES (#{r.tradeDate}, #{r.sectorCode}, #{r.sectorName}, #{r.closeValue}, #{r.pctChg}, #{r.note})
            ON DUPLICATE KEY UPDATE close_value = VALUES(close_value),
                                    pct_chg = VALUES(pct_chg),
                                    note = VALUES(note)
            """)
    int upsert(@Param("r") SectorDailyEntity row);

    /**
     * 按备注删除，用于清掉 V2 脚本里的示例数据。
     *
     * @param note 备注值
     * @return 删除行数
     */
    @Delete("DELETE FROM t_sector_daily WHERE note = #{note}")
    int deleteByNote(@Param("note") String note);
}
