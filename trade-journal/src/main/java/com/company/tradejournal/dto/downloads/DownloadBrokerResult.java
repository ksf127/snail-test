package com.company.tradejournal.dto.downloads;

/**
 * 单个券商的下载结果。
 *
 * @param name    券商名
 * @param status  OK / EMPTY（页面正常但没有触发下载）/ SKIPPED / FAILED
 * @param files   成功落盘的文件数
 * @param durationMs 耗时毫秒
 * @param message 失败原因或补充说明
 */
public record DownloadBrokerResult(String name, String status, int files, long durationMs, String message) {
}
