package com.company.tradejournal.dto.imports;

/**
 * 一列字段的识别结果。
 *
 * <p>【解决什么问题】券商导出的表头各家叫法不一样（「成交日期」「发生日期」「日期」），
 * 用户需要一眼确认「系统认成了哪一列」，否则解析错了也看不出来。
 *
 * @param field  系统内部字段名，如 tradeDate
 * @param label  中文名，如 交易日期
 * @param source 源文件表头；多个手续费列相加时用「 + 」连接
 */
public record ImportColumn(String field, String label, String source) {
}
