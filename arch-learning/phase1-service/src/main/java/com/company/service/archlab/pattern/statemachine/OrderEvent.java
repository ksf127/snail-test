package com.company.service.archlab.pattern.statemachine;

/**
 * 订单事件：驱动状态迁移的外部动作。
 *
 * <p>【解决什么问题】状态不能自己变，必须由"支付成功""发货""取消"这类外部动作触发。
 * 把动作也做成枚举，才能把"什么动作、从什么状态、到什么状态"表达成一张可校验的表。
 *
 * <p>【为什么这样设计】事件与状态分开建模，而不是合成一个 "PAY_SUCCESS" 字符串：
 * 事件是外部系统给的（支付回调、物流回调），状态是内部数据；
 * 两者混在一起后，就无法回答"这个状态下还能做什么"这种前端按钮需要的问题。
 * 不用枚举的话，拼写错误会在运行时才炸，而且无法穷举校验。
 *
 * <p>【怎么读这段代码】这四个事件就是 {@link OrderStateMachine} 迁移表的"列"。
 * 想试验：{@code GET /patterns/state-machine?state=CREATED&event=PAY} 得到 PAID；
 * 把 event 换成 SHIP 会返回 409，并附带当前状态允许的动作。
 */
public enum OrderEvent {
    PAY,
    SHIP,
    COMPLETE,
    CANCEL
}
