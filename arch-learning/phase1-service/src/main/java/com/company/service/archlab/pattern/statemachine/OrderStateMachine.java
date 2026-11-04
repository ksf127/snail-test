package com.company.service.archlab.pattern.statemachine;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 订单状态机（状态模式的数据驱动版本）。
 *
 * <p>【解决什么问题】"已支付的订单不能再支付""已取消的订单不能发货"这类规则，
 * 如果不集中管理，就会以 {@code if (status == PAID)} 的形式散落在支付、发货、取消、退款等各个入口；
 * 每个入口都只判一部分，迟早出现"某个入口忘了判断"的越权流转。
 *
 * <p>【为什么这样设计】和"一堆 if (status == PAID)"相比，状态机的价值是：
 * <ul>
 *   <li>合法迁移集中在一张表里，产品改流程只改表，不改散落各处的 if；</li>
 *   <li>非法迁移统一拒绝，而不是"某些入口忘了判断"；</li>
 *   <li>可以直接回答"当前状态下允许哪些动作"，前端按钮/接口文档都靠它。</li>
 * </ul>
 * 相比 GoF 的状态模式（每个状态一个类），数据驱动版在"规则就是一张表"的场景更省事：
 * 加一个状态只加一行 put，不需要新增类、也不需要维护状态之间的跳转代码。
 * 代价是迁移时的副作用（发消息、扣库存）无法直接写在表里——那需要状态机 + 事件/责任链组合，
 * 真实系统里这张表通常还会带上"迁移时要做的事"，Phase 2 的订单场景会用到。
 *
 * <p>【怎么读这段代码】先读 {@link #buildTransitions()} 的五行 put，把状态图画出来；
 * 再读 {@link #transit}：查表命中就是合法迁移，命中不了就是 409；
 * 最后看 {@link #allowedEvents}，它是同一个表的另一种读法（按行取 key）。
 * 注意整个 Map 在类加载时构建一次并冻结，运行期没有任何写操作，所以不需要加锁。
 */
@Component
public class OrderStateMachine {

    private static final Logger log = LoggerFactory.getLogger(OrderStateMachine.class);

    /**
     * 一次合法迁移的结果。
     *
     * @param from  迁移前状态
     * @param event 触发事件
     * @param to    迁移后状态
     */
    public record Transition(OrderState from, OrderEvent event, OrderState to) {
    }

    private static final Map<OrderState, Map<OrderEvent, OrderState>> TRANSITIONS = buildTransitions();

    /**
     * 执行一次状态迁移；只做校验和计算，不落地存储。
     *
     * @param from  当前状态
     * @param event 触发的事件
     * @return 迁移结果（from / event / to）
     * @throws BizException state 或 event 为空（PARAM_INVALID），
     *                      或该状态下不允许此事件（CONFLICT）时抛出；
     *                      冲突场景的错误信息里会带上当前状态允许的动作
     */
    public Transition transit(OrderState from, OrderEvent event) {
        if (from == null || event == null) {
            // 空值用 40001（参数错），非法迁移用 409（状态冲突），
            // 让调用方能区分"我传错了"和"业务上不允许"
            log.warn("【订单状态机】state 或 event 为空，state={}，event={}", from, event);
            throw new BizException(ErrorCode.PARAM_INVALID, "state 与 event 都不能为空");
        }
        // getOrDefault 而不是 get：终态（COMPLETED/CANCELLED）不在表里，
        // 用空 Map 兜底可以避免 NPE，同时自然地把"终态不可再迁移"表达出来
        OrderState to = TRANSITIONS.getOrDefault(from, Map.of()).get(event);
        if (to == null) {
            // 失败时把"可选动作"一起返回：前端不用再发一次请求就能纠正按钮状态
            log.warn("【订单状态机】非法迁移，from={}，event={}，允许的动作={}",
                    from, event, allowedEvents(from));
            throw new BizException(ErrorCode.CONFLICT,
                    "订单状态 " + from + " 不允许执行 " + event + "，可选动作: " + allowedEvents(from));
        }
        log.info("【订单状态机】迁移成功，{} --[{}]--> {}", from, event, to);
        return new Transition(from, event, to);
    }

    /**
     * 查询某状态下允许的动作。
     *
     * @param state 当前状态，可以为 null（返回空集合）
     * @return 不可变集合，合法动作；终态或无此状态时为空集，不会返回 null
     */
    public Set<OrderEvent> allowedEvents(OrderState state) {
        // 返回 unmodifiableSet 而不是副本：调用方只能读，防止 UI 层顺手把规则改掉
        return Collections.unmodifiableSet(TRANSITIONS.getOrDefault(state, Map.of()).keySet());
    }

    /**
     * 构建迁移表：这是整个状态机唯一的事实来源。
     *
     * <p>用 EnumMap 而不是 HashMap：key 是枚举，EnumMap 内部是数组，查询无哈希、无装箱，
     * 而且天然按枚举声明顺序遍历，调试时输出稳定。
     *
     * @return 只读的二维迁移表
     */
    private static Map<OrderState, Map<OrderEvent, OrderState>> buildTransitions() {
        Map<OrderState, Map<OrderEvent, OrderState>> table = new EnumMap<>(OrderState.class);
        // 待支付：能付款，也能取消
        put(table, OrderState.CREATED, OrderEvent.PAY, OrderState.PAID);
        put(table, OrderState.CREATED, OrderEvent.CANCEL, OrderState.CANCELLED);
        // 已支付：能发货，也能取消（这里按"未发货可取消"建模）
        put(table, OrderState.PAID, OrderEvent.SHIP, OrderState.SHIPPED);
        put(table, OrderState.PAID, OrderEvent.CANCEL, OrderState.CANCELLED);
        // 已发货：只能确认收货完成；故意不给 CANCEL，用来演示 409 分支
        put(table, OrderState.SHIPPED, OrderEvent.COMPLETE, OrderState.COMPLETED);
        // 外层也冻结：否则框架或反射可以替换整行，破坏"启动后不可变"的假设
        return Collections.unmodifiableMap(table);
    }

    /**
     * 往表里登记一条迁移，顺带处理"该起始状态的第一条迁移"。
     *
     * @param table 目标表
     * @param from  起始状态
     * @param event 事件
     * @param to    目标状态
     */
    private static void put(Map<OrderState, Map<OrderEvent, OrderState>> table,
                            OrderState from,
                            OrderEvent event,
                            OrderState to) {
        // computeIfAbsent：避免手写"先判 null 再 new"，也少一次重复查找
        table.computeIfAbsent(from, key -> new EnumMap<>(OrderEvent.class)).put(event, to);
    }
}
