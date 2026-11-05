package com.company.tradejournal.dto.imports;

import java.util.List;

/**
 * 导入解析结果。
 *
 * <p>【解决什么问题】把「读文件 → 认列 → 逐行校验 → 查重」全部放在服务端一次完成，
 * 前端只负责展示与确认，避免浏览器里再实现一套解析逻辑。
 *
 * @param type       trades / daily / positions
 * @param fileName   文件名，粘贴时为「粘贴内容」
 * @param total      数据行数（不含表头）
 * @param valid      可导入行数
 * @param duplicate  已存在行数
 * @param invalid    有问题的行数
 * @param columns    列识别结果
 * @param missing    未识别到的必填字段中文名
 * @param warnings   整体提示，如编码、表头缺失
 * @param rows       逐行明细
 */
public record ImportPreview(String type, String fileName, int total, int valid, int duplicate, int invalid,
                            List<ImportColumn> columns, List<String> missing, List<String> warnings,
                            List<ImportRow> rows) {
}
