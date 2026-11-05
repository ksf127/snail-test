package com.company.tradejournal.entity;

import java.time.LocalDate;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 纪律违背明细。
 *
 * <p>【解决什么问题】把违背次数汇总成一个数字，就看不出「是哪种违背」。
 * 按条记录后，才能回答「我到底是爱追涨，还是爱下移止损」——
 * 不同问题的改法完全不同，前者改信息源，后者改条件单。
 *
 * <p>【为什么这样设计】{@code itemLabel} 冗余存中文名称：
 * 统计报表要按月分组展示，若只存编码，前端还得维护一份映射，容易与后端不同步。
 */
@Data
@TableName("t_discipline_violation")
public class DisciplineViolationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate reviewDate;

    private String itemCode;

    private String itemLabel;
}
