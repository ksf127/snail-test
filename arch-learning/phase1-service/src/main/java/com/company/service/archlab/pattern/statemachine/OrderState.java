package com.company.service.archlab.pattern.statemachine;

/**
 * 订单状态：枚举而不是字符串/魔法值。
 * 好处是编译器能帮你挡住 "PAYED"/"PAID" 这种拼写事故。
 */
public enum OrderState {
    CREATED,
    PAID,
    SHIPPED,
    COMPLETED,
    CANCELLED
}
