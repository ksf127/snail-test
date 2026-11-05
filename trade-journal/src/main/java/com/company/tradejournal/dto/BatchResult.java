package com.company.tradejournal.dto;

import java.util.List;

/**
 * 批量录入结果。
 *
 * <p>【解决什么问题】一次导入十几行，不能因为其中一行写错就全部失败：
 * 用户需要知道「哪几行没进去、为什么」，改完只补那几行。
 *
 * <p>【为什么这样设计】成功数、失败数与错误明细一起返回。
 * 错误信息带行号，行号沿用源文件的行序（含表头偏移），对得上 Excel 里看到的行。
 *
 * @param total   参与处理的行数（不含表头）
 * @param success 成功入库的行数
 * @param failed  失败行数
 * @param errors  逐行错误说明，成功时为空列表
 */
public record BatchResult(int total, int success, int failed, List<String> errors) {

    public static BatchResult ok(int total) {
        return new BatchResult(total, total, 0, List.of());
    }
}
