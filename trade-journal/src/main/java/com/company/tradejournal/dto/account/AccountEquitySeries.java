package com.company.tradejournal.dto.account;

import java.util.List;

import com.company.tradejournal.dto.stats.EquityPoint;

/**
 * 单个账户的净值序列。
 *
 * <p>【解决什么问题】账户列表只给出「现在」，要看两个账户哪条曲线更稳，
 * 必须把各自的净值序列一起返回；让前端按账户挨个请求会把接口调用次数放大成账户数的倍数。
 *
 * @param accountId   账户 ID
 * @param accountName 账户名称
 * @param points      按日期升序的净值点
 */
public record AccountEquitySeries(Long accountId,
                                  String accountName,
                                  List<EquityPoint> points) {
}
