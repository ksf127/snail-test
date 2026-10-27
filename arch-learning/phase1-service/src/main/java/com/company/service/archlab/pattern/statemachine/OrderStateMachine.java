package com.company.service.archlab.pattern.statemachine;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.springframework.stereotype.Component;

/**
 * 订单状态机（状态模式的数据驱动版本）。
 *
 * <p>和"一堆 if (status == PAID)"相比，状态机的价值是：
 * <ul>
 *   <li>合法迁移集中在一张表里，产品改流程只改表，不改散落各处的 if；</li>
 *   <li>非法迁移统一拒绝，而不是"某些入口忘了判断"；</li>
 *   <li>可以直接回答"当前状态下允许哪些动作"，前端按钮/接口文档都靠它。</li>
 * </ul>
 *
 * <p>真实系统里这张表通常还会带上"迁移时要做的事"（发消息、扣库存），
 * 那就是状态机 + 责任链/事件的组合，Phase 2 的订单场景会用到。
 */
@Component
public class OrderStateMachine {

    public record Transition(OrderState from, OrderEvent event, OrderState to) {
    }

    private static final Map<OrderState, Map<OrderEvent, OrderState>> TRANSITIONS = buildTransitions();

    public Transition transit(OrderState from, OrderEvent event) {
        if (from == null || event == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "state 与 event 都不能为空");
        }
        OrderState to = TRANSITIONS.getOrDefault(from, Map.of()).get(event);
        if (to == null) {
            throw new BizException(ErrorCode.CONFLICT,
                    "订单状态 " + from + " 不允许执行 " + event + "，可选动作: " + allowedEvents(from));
        }
        return new Transition(from, event, to);
    }

    public Set<OrderEvent> allowedEvents(OrderState state) {
        return Collections.unmodifiableSet(TRANSITIONS.getOrDefault(state, Map.of()).keySet());
    }

    private static Map<OrderState, Map<OrderEvent, OrderState>> buildTransitions() {
        Map<OrderState, Map<OrderEvent, OrderState>> table = new EnumMap<>(OrderState.class);
        put(table, OrderState.CREATED, OrderEvent.PAY, OrderState.PAID);
        put(table, OrderState.CREATED, OrderEvent.CANCEL, OrderState.CANCELLED);
        put(table, OrderState.PAID, OrderEvent.SHIP, OrderState.SHIPPED);
        put(table, OrderState.PAID, OrderEvent.CANCEL, OrderState.CANCELLED);
        put(table, OrderState.SHIPPED, OrderEvent.COMPLETE, OrderState.COMPLETED);
        return Collections.unmodifiableMap(table);
    }

    private static void put(Map<OrderState, Map<OrderEvent, OrderState>> table,
                            OrderState from,
                            OrderEvent event,
                            OrderState to) {
        table.computeIfAbsent(from, key -> new EnumMap<>(OrderEvent.class)).put(event, to);
    }
}
