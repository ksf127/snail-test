package com.company.tradejournal.dto.downloads;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次自动下载的结果。
 *
 * @param trigger      定时·09:33 或 手动触发
 * @param startedAt    开始时间
 * @param finishedAt   结束时间
 * @param brokers      实际执行的券商数
 * @param files        落盘文件数
 * @param importedRows 紧接着跑一次自动同步写入的行数
 * @param details      每个券商的结果
 */
public record DownloadRunResult(String trigger, LocalDateTime startedAt, LocalDateTime finishedAt,
                                int brokers, int files, int importedRows,
                                List<DownloadBrokerResult> details) {
}
