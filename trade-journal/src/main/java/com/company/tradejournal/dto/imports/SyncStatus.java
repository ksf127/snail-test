package com.company.tradejournal.dto.imports;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动同步的现状：监听哪些目录、几点扫、上次结果、最近处理过的文件。
 */
public record SyncStatus(boolean enabled, boolean running, Long accountId,
                         List<String> directories, List<String> schedulePoints,
                         LocalDateTime lastScanAt, SyncRunResult lastRun, List<SyncFileLog> recent) {
}
