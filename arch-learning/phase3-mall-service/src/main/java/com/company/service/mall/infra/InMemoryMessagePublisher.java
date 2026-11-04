package com.company.service.mall.infra;

import com.company.service.mall.domain.OutboxMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 内存版 Broker，默认实现，单测与本地演示零外部依赖。
 *
 * <p>【解决什么问题】outbox 中继需要验证"发送失败 → 退避重试 → 最终成功/死信"和
 * "至少一次投递 → 消费端幂等"这两条链路，但本地不可能为了看日志去搭 RabbitMQ。
 *
 * <p>【为什么这样设计】它同时模拟两件真实世界的事：
 * <ul>
 *   <li>{@link #failNext(int)}：Broker 短暂不可用，用来验证 outbox 的重试与死信</li>
 *   <li>{@link #subscribe}：消费者订阅，用来验证"至少一次投递 + 消费端幂等"</li>
 * </ul>
 * 注意投递是同步的：publish 返回即代表消费者已经处理完。
 * 真实 MQ 是异步的，但这对 outbox 的状态机没有影响 ——
 * outbox 只关心 Broker 是否确认收到，不关心消费者处理到哪一步。
 *
 * <p>【怎么读这段代码】{@link #publish} 先扣一次"故障余额"，有余额就抛异常（中继会据此重试）；
 * 否则记入已投递列表并同步回调所有订阅者。
 * 日志一律 debug —— 内存实现每个操作都打 info 会刷屏。
 */
public class InMemoryMessagePublisher implements MessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMessagePublisher.class);

    private final AtomicInteger remainingFailures = new AtomicInteger();

    private final List<OutboxMessage> published = new CopyOnWriteArrayList<>();

    private final List<Consumer<OutboxMessage>> listeners = new CopyOnWriteArrayList<>();

    /**
     * 让接下来的 n 次投递失败，模拟 Broker 抖动 / 网络分区。
     *
     * @param times 失败次数，负数按 0 处理
     */
    public void failNext(int times) {
        remainingFailures.set(Math.max(times, 0));
        log.debug("注入 Broker 故障: 接下来 {} 次投递将失败", Math.max(times, 0));
    }

    /**
     * 订阅消息（同步消费者）。幂等：同一 listener 重复订阅会收到多次。
     */
    public void subscribe(Consumer<OutboxMessage> listener) {
        listeners.add(listener);
    }

    public List<OutboxMessage> published() {
        return List.copyOf(published);
    }

    /**
     * 投递消息。模拟 Broker：有故障余额时抛异常，交给调用方（outbox 中继）重试。
     *
     * <p>幂等性：不幂等，每次成功调用都会追加一条投递记录并触发所有订阅者。
     *
     * @param message 待投递消息
     * @throws IllegalStateException 还有剩余故障次数
     */
    @Override
    public void publish(OutboxMessage message) {
        int failures = remainingFailures.getAndUpdate(current -> current > 0 ? current - 1 : 0);
        if (failures > 0) {
            log.debug("内存 Broker 拒绝投递: id={}, 剩余故障次数={}", message.id(), failures - 1);
            throw new IllegalStateException("模拟 Broker 不可用，剩余失败次数: " + (failures - 1));
        }
        published.add(message);
        // 同步通知订阅者：模拟"投递即被消费"，用于验证消费端幂等
        listeners.forEach(listener -> listener.accept(message));
        log.debug("内存 Broker 投递成功: id={}, topic={}, 订阅者={}",
                message.id(), message.topic(), listeners.size());
    }

    @Override
    public String provider() {
        return "memory";
    }
}
