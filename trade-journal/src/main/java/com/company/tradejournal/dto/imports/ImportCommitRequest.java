package com.company.tradejournal.dto.imports;

import java.util.List;
import java.util.Map;

/**
 * 确认导入的请求体。
 *
 * <p>【解决什么问题】预览和入库之间可能有几分钟间隔，这期间用户可能在别的页签改过数据，
 * 所以提交时不能直接相信预览结果，必须带着原始行重新校验。
 *
 * @param type            导入类型：trades / daily / positions
 * @param accountId       目标账户，为空落默认账户 1
 * @param defaultReason   成交流水缺少理由时使用的默认值
 * @param skipDuplicates  重复记录是否跳过，默认 true
 * @param rows            预览返回的 data 列表
 */
public record ImportCommitRequest(String type, Long accountId, String defaultReason, Boolean skipDuplicates,
                                  List<Map<String, String>> rows) {
}
