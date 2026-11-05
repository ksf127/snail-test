package com.company.tradejournal.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 每日复盘文字部分：一天一条，重复提交视为修改。
 *
 * <p>【解决什么问题】盘感和情绪这类信息无法结构化，但对复盘极重要
 * （示例里「五个违规发生在同一个小时」就是从情绪记录里看出来的）。
 * 所以这类内容单独存一张表，允许自由书写，不参与统计计算。
 *
 * <p>【为什么这样设计】{@code violationCount} 冗余存一份：
 * 它同时能从 {@code t_discipline_violation} 数出来，但日复盘列表需要频繁展示这个数，
 * 冗余一次可以避免每次列表都做子查询。写入时由 Service 保证两者一致。
 */
@Data
@TableName("t_daily_review")
public class DailyReviewEntity {

    @TableId(type = IdType.INPUT)
    private LocalDate reviewDate;

    private String marketNote;

    private String strongSector;

    private String weakSector;

    private String missedOpportunity;

    private String emotion;

    private String lesson;

    private String tomorrowTodo;

    /** 当日违背纪律次数，写入时按明细条数回填。 */
    private Integer violationCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
