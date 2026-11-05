package com.company.tradejournal.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 每日账户净值。
 *
 * <p>【解决什么问题】最大回撤是唯一能回答「这套做法我扛不扛得住」的指标，
 * 而回撤只能从「每日净值序列」算出来。它既不在成交表里，也不在复盘表里，
 * 所以必须单独存一张按日期的净值表。
 *
 * <p>【为什么这样设计】主键是 {@code (accountId, tradeDate)}：一天一条只对单账户成立，
 * 多账户下每个账户每天各一条，合并视图在查询时按日期 SUM 出来。
 * 净值由「现金 + 持仓市值」得出，提交复盘时自动计算，避免手填时把现金漏掉。
 *
 * <p>【怎么读这段代码】复合主键不能用 {@code selectById / updateById}，
 * 写入统一走 {@code AccountDailyMapper#upsert}（INSERT ... ON DUPLICATE KEY UPDATE），
 * 查最新一条走 {@code selectLatest}。
 */
@Data
@TableName("t_account_daily")
public class AccountDailyEntity {

    /** 所属账户，复合主键的第一列。 */
    private Long accountId;

    /** 交易日，同一账户内唯一。 */
    private LocalDate tradeDate;

    /** 账户净值 = 现金 + 持仓市值。 */
    private BigDecimal equity;

    private BigDecimal cash;

    private String note;
}
