package com.company.tradejournal.service;

import java.time.LocalDate;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.company.tradejournal.entity.SectorDailyEntity;
import com.company.tradejournal.mapper.SectorDailyMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 板块行情参考查询（只读）。
 *
 * <p>【解决什么问题】复盘要判断「持仓没涨是选股问题还是板块问题」，
 * 就需要一个不受自己情绪影响的板块基准。这里只提供读取，写入由行情脚本或手工维护。
 *
 * <p>【为什么这样设计】不加缓存、不做聚合：区间通常只有几周、几十行，
 * 直接查库最简单；聚合放到调用方或前端，避免这里长出一套与统计接口重复的口径。
 */
@Service
public class SectorRefService {

    private static final Logger log = LoggerFactory.getLogger(SectorRefService.class);

    private final SectorDailyMapper sectorDailyMapper;

    public SectorRefService(SectorDailyMapper sectorDailyMapper) {
        this.sectorDailyMapper = sectorDailyMapper;
    }

    /**
     * 查询区间内的板块行情。
     *
     * @param from       起始日期（含）
     * @param to         结束日期（含）
     * @param sectorCode 板块代码，为空则返回该区间全部板块
     * @return 按交易日升序的行情
     */
    public List<SectorDailyEntity> list(LocalDate from, LocalDate to, String sectorCode) {
        List<SectorDailyEntity> rows = sectorDailyMapper.selectList(
                Wrappers.<SectorDailyEntity>lambdaQuery()
                        .ge(SectorDailyEntity::getTradeDate, from)
                        .le(SectorDailyEntity::getTradeDate, to)
                        .eq(sectorCode != null && !sectorCode.isBlank(),
                                SectorDailyEntity::getSectorCode, sectorCode)
                        .orderByAsc(SectorDailyEntity::getTradeDate));
        log.info("【参考·板块】{} ~ {}｜{}｜{} 条", from, to,
                sectorCode == null || sectorCode.isBlank() ? "全部板块" : sectorCode, rows.size());
        return rows;
    }
}
