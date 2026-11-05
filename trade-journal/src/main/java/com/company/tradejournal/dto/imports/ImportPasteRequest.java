package com.company.tradejournal.dto.imports;

/**
 * 粘贴表格的预览请求。
 *
 * @param type      trades / daily / positions
 * @param accountId 目标账户
 * @param text      从 Excel 或网页里复制的制表符文本
 */
public record ImportPasteRequest(String type, Long accountId, String text) {
}
