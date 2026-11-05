package com.company.tradejournal.controller;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import com.company.common.core.ApiResult;
import com.company.tradejournal.dto.BatchResult;
import com.company.tradejournal.dto.PageResult;
import com.company.tradejournal.dto.TradeRequest;
import com.company.tradejournal.entity.PositionEntity;
import com.company.tradejournal.entity.TradeEntity;
import com.company.tradejournal.service.TradeImportService;
import com.company.tradejournal.service.TradeService;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 成交与持仓接口。
 *
 * <p>【怎么用】每次成交后立即 {@code POST /trades}，不要攒到晚上：
 * 成交当天的价格、费用、理由是最清楚的，隔天补记必然会漏费用。
 * 已经攒了一批的，用 {@code POST /trades/batch} 或上传 Excel。
 */
@RestController
@RequestMapping("/trades")
public class TradeController {

    private final TradeService tradeService;

    private final TradeImportService tradeImportService;

    public TradeController(TradeService tradeService, TradeImportService tradeImportService) {
        this.tradeService = tradeService;
        this.tradeImportService = tradeImportService;
    }

    /**
     * 记录一笔成交，系统同步维护持仓与已实现盈亏。
     *
     * @param request 成交内容
     * @return 落库后的成交记录
     */
    @PostMapping
    public ApiResult<TradeEntity> record(@Valid @RequestBody TradeRequest request) {
        return ApiResult.ok(tradeService.record(request));
    }

    /**
     * 分页查询成交明细，支持关键字、方向、计划内外、盈亏与排序。
     *
     * @param from      起始日期，格式 yyyy-MM-dd
     * @param to        结束日期，格式 yyyy-MM-dd
     * @param keyword   代码或名称关键字
     * @param side      BUY / SELL
     * @param isPlanned 是否计划内
     * @param pnl       WIN / LOSS / NONE
     * @param sort      date（默认）/ pnl
     * @param page      页码，从 1 开始
     * @param size      每页条数
     * @param accountId 账户 ID，为空表示全部账户
     * @return 分页结果
     */
    @GetMapping
    public ApiResult<PageResult<TradeEntity>> list(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "side", required = false) String side,
            @RequestParam(value = "isPlanned", required = false) Boolean isPlanned,
            @RequestParam(value = "pnl", required = false) String pnl,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size,
            @RequestParam(value = "accountId", required = false) Long accountId) {
        return ApiResult.ok(tradeService.list(from, to, keyword, side, isPlanned, pnl, sort, page, size, accountId));
    }

    /**
     * 批量录入，单行失败不影响其他行。
     *
     * @param requests 成交列表
     * @return 成功数、失败数与逐行错误
     */
    @PostMapping("/batch")
    public ApiResult<BatchResult> batch(@RequestBody List<TradeRequest> requests) {
        return ApiResult.ok(tradeImportService.batch(requests));
    }

    /**
     * 上传 Excel（.xlsx）或 CSV 导入成交。
     *
     * @param file 上传文件
     * @return 导入结果
     * @throws IOException 文件读取失败
     */
    @PostMapping("/import")
    public ApiResult<BatchResult> importFile(@RequestParam("file") MultipartFile file) throws IOException {
        return ApiResult.ok(tradeImportService.importFile(file));
    }

    /**
     * 下载导入模板。
     *
     * @return xlsx 文件
     * @throws IOException 生成失败
     */
    @GetMapping("/import/template")
    public ResponseEntity<byte[]> template() throws IOException {
        String filename = URLEncoder.encode("成交导入模板.xlsx", StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(tradeImportService.template());
    }

    /**
     * 当前持仓。
     *
     * @param accountId 账户 ID，为空表示全部账户
     * @return 持仓列表
     */
    @GetMapping("/positions")
    public ApiResult<List<PositionEntity>> positions(
            @RequestParam(value = "accountId", required = false) Long accountId) {
        return ApiResult.ok(tradeService.positions(accountId));
    }
}
