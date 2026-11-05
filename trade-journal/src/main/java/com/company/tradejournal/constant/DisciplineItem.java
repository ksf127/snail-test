package com.company.tradejournal.constant;

/**
 * 纪律违背项。
 *
 * <p>【解决什么问题】把「今天我违规了」变成固定选项，才能按月统计出「我最常犯的是哪一条」。
 * 自由文本无法聚合。
 *
 * <p>【为什么是这七条】直接对应每日复盘模板里的自检表，前后口径一致，
 * 不会出现「模板里勾了追涨、系统里选不出来」的情况。
 */
public enum DisciplineItem {

    CHANGE_PLAN("盘中改计划"),
    OFF_PLAN("计划外交易"),
    CHASE("追涨杀跌"),
    FOLLOW_TIP("听消息下单"),
    AVERAGE_DOWN("亏损加仓"),
    OVER_POSITION("超仓位上限"),
    OVERTRADE("盯盘过频");

    private final String label;

    DisciplineItem(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
