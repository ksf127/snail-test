package com.company.tradejournal.controller;

import java.time.LocalDate;
import java.util.List;

import com.company.common.core.ApiResult;
import com.company.tradejournal.entity.SectorDailyEntity;
import com.company.tradejournal.service.SectorRefService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 板块行情参考接口（只读）。
 */
@RestController
@RequestMapping("/refs/sectors")
public class SectorRefController {

    private final SectorRefService sectorRefService;

    public SectorRefController(SectorRefService sectorRefService) {
        this.sectorRefService = sectorRefService;
    }

    /**
     * 查询区间板块行情。
     *
     * @param from   起始日期，格式 yyyy-MM-dd
     * @param to     结束日期，格式 yyyy-MM-dd
     * @param sector 板块代码，可空；银行板块为 399986
     * @return 按交易日升序的行情
     */
    @GetMapping
    public ApiResult<List<SectorDailyEntity>> list(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "sector", required = false) String sector) {
        return ApiResult.ok(sectorRefService.list(from, to, sector));
    }
}
