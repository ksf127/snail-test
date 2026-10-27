package com.company.service.mall.infra;

import com.company.service.mall.domain.OutboxMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 内存版 Broker，默认实现，单测与本地演示零外部依赖。
 *
 * <p>它同时模拟两件真实世界的事：
 * <ul>
 *   <li>{@link #failNext(int)}：Broker 短暂不可用，用来验证 outbox 的重试与死信</li>
 *   <li>{@link #subscribe}：消费者订阅，用来验证"至少一次投递 + 消费端幂等"</li>
 * </ul>
 *
 * <p>注意投递是同步的：publish 返回即代表消费者已经处理完。
 * 真实 MQ 是异步的，但这对 outbox 的状态机没有影响 ——
 * outbox 只关心 Broker 是否确认收到，不关心消费者处理到哪一步。
 */
public class InMemoryMessagePublisher implements MessagePublisher {

    private final AtomicInteger remainingFailures = new AtomicInteger();

    private final List<OutboxMessage> published = new CopyOnWriteArrayList<>();

    private final List<Consumer<OutboxMessage>> listeners = new CopyOnWriteArrayList<>();

    /**
     * 让接下来的 n 次投递失败，模拟 Broker 抖动 / 网络分区。
     */
    public void failNext(int times) {
        remainingFailures.set(Math.max(times, 0));
    }

    public void subscribe(Consumer<OutboxMessage> listener) {
        listeners.add(listener);
    }

    public List<OutboxMessage> published() {
        return List.copyOf(published);
    }

    @Override
    public void publish(OutboxMessage message) {
        int failures = remainingFailures.getAndUpdate(current -> current > 0 ? current - 1 : 0);
        if (failures > 0) {
            throw new IllegalStateException("模拟 Broker 不可用，剩余失败次数: " + (failures - 1));
        }
        published.add(message);
        listeners.forEach(listener -> listener.accept(message));
    }

    @Override
    public String provider() {
        return "memory";
    }
}
