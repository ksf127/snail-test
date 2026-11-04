package com.company.service.archlab.pattern.statemachine;

/**
 * 订单状态：枚举而不是字符串/魔法值。
 * 好处是编译器能帮你挡住 "PAYED"/"PAID" 这种拼写事故。
 *
 * <p>【解决什么问题】订单状态是典型的"取值有限且必须合法"的字段。
 * 用 String 存，"PAID" 和 "PAYED" 都能塞进去，数据库里迟早出现第三种写法；
 * 用枚举，非法值在编译期或反序列化阶段就被挡住。
 *
 * <p>【为什么这样设计】这里刻意不把"每个状态能做什么"写在枚举里（那种写法叫状态模式的对象版），
 * 而是让 {@link OrderStateMachine} 用一张集中迁移表描述。
 * 原因是：状态 × 事件 × 目标状态本质是一张二维表，表比方法更直观，
 * 而且产品改流程时只需要改表，不用在五个枚举常量里翻找逻辑。
 *
 * <p>【怎么读这段代码】只有状态清单本身。真正的规则在 {@link OrderStateMachine#buildTransitions()}：
 * 把这份清单和那张表对着看，就能画出完整的状态流转图。
 * 注意 {@code CANCELLED} 和 {@code COMPLETED} 是终态，表里没有以它们为起点的迁移。
 */
public enum OrderState {
    CREATED,
    PAID,
    SHIPPED,
    COMPLETED,
    CANCELLED
}
