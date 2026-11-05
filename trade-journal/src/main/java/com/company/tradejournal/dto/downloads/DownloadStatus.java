package com.company.tradejournal.dto.downloads;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动下载的现状：配了哪些券商、文件落到哪、几点跑、上次结果。
 */
public record DownloadStatus(boolean enabled, boolean running, String channel,
                             List<String> directories, List<String> schedulePoints,
                             LocalDateTime lastRunAt, DownloadRunResult lastRun,
                             List<BrokerConfigView> brokers) {
}
