package com.company.tradejournal.dto.imports;

import java.util.List;

/**
 * 确认导入的结果。
 *
 * @param type    导入类型
 * @param total   提交行数
 * @param success 成功写入行数
 * @param skipped 因重复被跳过行数
 * @param failed  失败行数
 * @param errors  逐行错误，成功时为空
 */
public record ImportCommitResult(String type, int total, int success, int skipped, int failed, List<String> errors) {
}
