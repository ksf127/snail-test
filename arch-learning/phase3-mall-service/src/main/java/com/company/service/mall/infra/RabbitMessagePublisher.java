package com.company.service.mall.infra;

import com.company.service.mall.domain.OutboxMessage;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RabbitMQ 出口：等 Broker 的 Confirm 再返回。
 *
 * <p>为什么必须等 Confirm：
 * {@code convertAndSend} 只是把字节写进 TCP 缓冲，Broker 可能没收到、交换机可能不存在、
 * 队列可能没绑定，这些都不会在方法调用时抛异常。只有 correlated confirm 能把
 * "ack / nack" 明确带回来，outbox 才有机会对失败的消息重试而不是误标 SENT。
 *
 * <p>注意 mandatory + returns 处理的是"到达交换机但没有路由到任何队列"，
 * 与 confirm 是两个不同层面的保证，company-mq-starter 已经统一配置。
 */
public class RabbitMessagePublisher implements MessagePublisher {

    private final RabbitTemplate rabbitTemplate;

    private final String exchange;

    private final Duration confirmTimeout;

    public RabbitMessagePublisher(RabbitTemplate rabbitTemplate, String exchange, Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void publish(OutboxMessage message) {
        CorrelationData correlation = new CorrelationData(Long.toString(message.id()));
        try {
            rabbitTemplate.convertAndSend(exchange, message.topic(), message.payload(), raw -> {
                raw.getMessageProperties().setMessageId(Long.toString(message.id()));
                return raw;
            }, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("Broker 未确认: outboxId=" + message.id()
                        + ", reason=" + confirm.getReason());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Confirm 被中断: outboxId=" + message.id(), e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("等待 Confirm 超时(" + confirmTimeout + "): outboxId=" + message.id(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("等待 Confirm 失败: outboxId=" + message.id(), e.getCause());
        }
    }

    @Override
    public String provider() {
        return "rabbit";
    }
}
