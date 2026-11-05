package com.company.tradejournal.controller;

import java.io.IOException;

import com.company.common.core.ApiResult;
import com.company.tradejournal.dto.imports.ImportCommitRequest;
import com.company.tradejournal.dto.imports.ImportCommitResult;
import com.company.tradejournal.dto.imports.ImportPasteRequest;
import com.company.tradejournal.dto.imports.ImportPreview;
import com.company.tradejournal.dto.imports.SyncRunResult;
import com.company.tradejournal.dto.imports.SyncStatus;
import com.company.tradejournal.service.DataImportService;
import com.company.tradejournal.service.FileSyncService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 一键导入接口。
 *
 * <p>【怎么用】先 {@code POST /imports/preview} 拿预览（不写库），确认无误后再
 * {@code POST /imports/commit}。两步之间不会锁数据，所以提交阶段会重新校验。
 *
 * <p>【为什么不直接上传即入库】券商导出经常带一行合计、一行备注，
 * 直接入库出了问题只能手工删。预览这一步是给用户一次「看清楚再按确认」的机会。
 */
@RestController
@RequestMapping("/imports")
public class ImportController {

    private final DataImportService dataImportService;

    private final FileSyncService fileSyncService;

    public ImportController(DataImportService dataImportService, FileSyncService fileSyncService) {
        this.dataImportService = dataImportService;
        this.fileSyncService = fileSyncService;
    }

    /**
     * 上传文件并解析预览，不写库。
     *
     * @param file      券商导出的 .xlsx / .csv / .txt
     * @param type      trades / daily / positions
     * @param accountId 目标账户，为空落默认账户
     * @return 预览结果
     * @throws IOException 文件读取失败
     */
    @PostMapping("/preview")
    public ApiResult<ImportPreview> preview(@RequestParam("file") MultipartFile file,
                                            @RequestParam("type") String type,
                                            @RequestParam(value = "accountId", required = false) Long accountId)
            throws IOException {
        return ApiResult.ok(dataImportService.preview(type, accountId, file));
    }

    /**
     * 粘贴表格文本并解析预览，不写库。
     *
     * @param request 粘贴内容
     * @return 预览结果
     */
    @PostMapping("/paste")
    public ApiResult<ImportPreview> paste(@RequestBody ImportPasteRequest request) {
        return ApiResult.ok(dataImportService.previewPaste(request.type(), request.accountId(), request.text()));
    }

    /**
     * 确认导入。
     *
     * @param request 预览返回的行
     * @return 成功、跳过、失败统计
     */
    @PostMapping("/commit")
    public ApiResult<ImportCommitResult> commit(@RequestBody ImportCommitRequest request) {
        return ApiResult.ok(dataImportService.commit(request));
    }

    /**
     * 自动同步现状：监听目录、定时时点、上次结果、最近处理记录。
     *
     * @return 自动同步状态
     */
    @GetMapping("/sync/status")
    public ApiResult<SyncStatus> syncStatus() {
        return ApiResult.ok(fileSyncService.status());
    }

    /**
     * 立刻扫描下载目录并导入，不用等定时任务。
     *
     * @return 本次扫描结果
     */
    @PostMapping("/sync/run")
    public ApiResult<SyncRunResult> syncRun() {
        return ApiResult.ok(fileSyncService.runNow());
    }
}
