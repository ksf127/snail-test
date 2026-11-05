package com.company.tradejournal.dto.imports;

import java.util.Map;

/**
 * 预览里的一行。
 *
 * <p>【解决什么问题】导入前必须让用户看到「哪几行会进库、哪几行会跳过、哪几行有问题」，
 * 而不是点完导入了才发现有一半没进去。
 *
 * <p>【为什么这样设计】{@code data} 存的是已经归一化后的值（日期 yyyy-MM-dd、方向 BUY/SELL、
 * 金额去千分位），前端直接展示，提交时原样回传，服务端再校验一遍。
 *
 * @param rowNo   源文件行号（含表头），对得上 Excel 里看到的行
 * @param status  VALID 可导入 / DUPLICATE 已存在 / INVALID 有问题
 * @param message 说明，正常行为空
 * @param data    归一化后的字段
 */
public record ImportRow(int rowNo, String status, String message, Map<String, String> data) {
}
