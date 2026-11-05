package com.company.tradejournal.dto.imports;

import java.time.LocalDateTime;

/**
 * 自动同步处理过的一个文件。
 *
 * <p>{@code status} 取值：{@code IMPORTED} 有行写入、{@code SKIPPED} 无需导入（重复或认不出类型）、
 * {@code FAILED} 全部行都失败。前端按这个字段决定徽标颜色。
 */
public record SyncFileLog(Long id, String fileName, String filePath, String type, String status,
                          int total, int success, int skipped, int failed, String message,
                          LocalDateTime scannedAt, LocalDateTime importedAt) {
}
