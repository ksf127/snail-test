package com.company.tradejournal.dto.imports;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次自动同步扫描的结果。
 *
 * <p>文件级统计（scanned / imported / skipped / failed）与行级统计（rows / success / duplicate / failedRows）
 * 分开给：文件里有 1 行成功也算 imported，用户需要知道到底写进去多少行。
 */
public record SyncRunResult(String trigger, LocalDateTime startedAt, LocalDateTime finishedAt,
                            int scanned, int imported, int skippedFiles, int failedFiles,
                            int rows, int success, int duplicate, int failedRows,
                            List<String> messages) {
}
