package com.company.tradejournal.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 账户：成交、持仓、净值都归属到某一个账户。
 *
 * <p>【解决什么问题】单账户模型下所有数据混在一起，模拟盘和真金白银的收益算在同一个胜率里，
 * 结论会失真。这里用一个独立的账户维度把数据隔开，同时保留「全部账户」的合并视图。
 *
 * <p>【为什么这样设计】{@code initialCapital} 单独存下来，是因为收益率的定义必须有一个固定分母；
 * 用「当前净值」当分母会让收益率随账户涨跌漂移，历史月份之间就无法比较了。
 * {@code active} 只做软停用、不做删除：删账户会让历史成交变成孤儿，统计直接对不上。
 */
@Data
@TableName("t_account")
public class AccountEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String broker;

    private String currency;

    /** 初始资金，收益率的固定分母。 */
    private BigDecimal initialCapital;

    private String note;

    /** 是否启用，停用后不再出现在账户列表里，但历史数据保留。 */
    private Boolean active;

    private LocalDateTime createdAt;
}
