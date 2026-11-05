package com.company.tradejournal.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 交易计划：盘前写下的「若 X 则 Y」。
 *
 * <p>【解决什么问题】计划外交易是散户最主要的亏损来源，但事后没人能说清「这笔到底是不是临时起意」。
 * 盘前把计划落库，成交时带 {@code planId}，事后就能精确统计计划内与计划外的胜率差。
 *
 * <p>【为什么这样设计】状态只有 PENDING / EXECUTED / CANCELLED 三个。
 * 不做「部分执行」，因为一笔计划对应一笔成交，部分成交属于执行细节，不影响复盘判读。
 */
@Data
@TableName("t_trade_plan")
public class TradePlanEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 计划日期，盘前填写当天。 */
    private LocalDate planDate;

    private String symbol;

    private String name;

    /** 触发条件，例如「跌破 18.20」。 */
    private String triggerDesc;

    /** 触发后的动作，例如「卖出全部 500 股」。 */
    private String actionDesc;

    /** 依据，必须写得可证伪，否则事后无法判断是运气还是体系。 */
    private String reason;

    /** PENDING / EXECUTED / CANCELLED。 */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
