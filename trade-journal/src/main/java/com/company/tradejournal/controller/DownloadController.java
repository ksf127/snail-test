package com.company.tradejournal.controller;

import com.company.common.core.ApiResult;
import com.company.tradejournal.dto.downloads.DownloadRunResult;
import com.company.tradejournal.dto.downloads.DownloadStatus;
import com.company.tradejournal.service.BrokerDownloadService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自动下载接口：到点打开券商导出页点导出，落盘后自动入库。
 *
 * <p>首次使用顺序：{@code POST /downloads/login?broker=华泰} 登录一次 → 关闭窗口 →
 * {@code POST /downloads/run} 验证能导出 → 之后交给定时任务。
 */
@RestController
@RequestMapping("/downloads")
public class DownloadController {

    private final BrokerDownloadService downloadService;

    public DownloadController(BrokerDownloadService downloadService) {
        this.downloadService = downloadService;
    }

    /**
     * 自动下载现状：配了哪些券商、文件落到哪、几点跑、上次结果。
     *
     * @return 状态
     */
    @GetMapping("/status")
    public ApiResult<DownloadStatus> status() {
        return ApiResult.ok(downloadService.status());
    }

    /**
     * 立即下载并导入，不用等定时任务。
     *
     * @param broker 只跑某个券商，为空跑全部启用的
     * @return 本次结果
     */
    @PostMapping("/run")
    public ApiResult<DownloadRunResult> run(@RequestParam(value = "broker", required = false) String broker) {
        return ApiResult.ok(downloadService.runNow(broker));
    }

    /**
     * 打开有头浏览器登录一次，登录后关闭窗口即可。
     *
     * @param broker 券商名
     * @return 提示文案
     */
    @PostMapping("/login")
    public ApiResult<String> login(@RequestParam("broker") String broker) {
        downloadService.openLogin(broker);
        return ApiResult.ok("已打开浏览器，请完成登录后关闭窗口，登录态会保存在本机");
    }
}
