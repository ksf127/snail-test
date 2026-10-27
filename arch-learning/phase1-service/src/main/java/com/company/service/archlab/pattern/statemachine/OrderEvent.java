package com.company.service.archlab.pattern.statemachine;

/**
 * 订单事件：驱动状态迁移的外部动作。
 */
public enum OrderEvent {
    PAY,
    SHIP,
    COMPLETE,
    CANCEL
}
